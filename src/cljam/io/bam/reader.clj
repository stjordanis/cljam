(ns cljam.io.bam.reader
  (:require [cljam.io.protocols :as protocols]
            [cljam.io.sam.util :refer [ref-id ref-name parse-header get-end]]
            [cljam.io.bam-index :refer [get-spans]]
            [cljam.io.bam-index.core :as bai]
            [cljam.io.bam.common :refer [fixed-block-size]]
            [cljam.io.bam.decoder :as decoder]
            [cljam.io.util.lsb :as lsb])
  (:import [java.io Closeable EOFException]
           java.nio.ByteBuffer
           bgzf4j.BGZFInputStream))

(declare read-alignments-sequentially*
         read-alignments*
         read-blocks-sequentially*
         read-blocks*)

;; BAMReader
;; ---------

(deftype BAMReader [f header refs reader data-reader index-delay start-pos]
  Closeable
  (close [this]
    (.close ^Closeable (.reader this)))
  protocols/IReader
  (reader-path [this]
    (.f this))
  (read [this]
    (protocols/read this {}))
  (read [this option]
    (protocols/read-alignments this {} option))
  protocols/IAlignmentReader
  (read-header [this]
    (.header this))
  (read-refs [this]
    (.refs this))
  (read-alignments [this]
    (protocols/read-alignments this {} {}))
  (read-alignments [this region]
    (protocols/read-alignments this region {}))
  (read-alignments [this
                    {:keys [chr start end]
                     :or {chr nil
                          start 1
                          end Long/MAX_VALUE}}
                    {:keys [depth]
                     :or {depth :deep}}]
    (if (nil? chr)
      (read-alignments-sequentially* this depth)
      (read-alignments* this chr start end depth)))
  (read-blocks [this]
    (protocols/read-blocks this {} {}))
  (read-blocks [this region]
    (protocols/read-blocks this region {}))
  (read-blocks [this
                {:keys [chr start end]
                 :or {chr nil
                      start 1
                      end Long/MAX_VALUE}}
                {:keys [mode]
                 :or {mode :normal}}]
    (if (nil? chr)
      (read-blocks-sequentially* this mode)
      (read-blocks* this chr start end)))
  protocols/IRegionReader
  (read-in-region [this region]
    (protocols/read-in-region this region {}))
  (read-in-region [this region option]
    (protocols/read-alignments this region option)))

;; Reading a single block
;; --------------------

(defn- read-alignment-block
  "Reads an alignment block, returning a map including the block size and the
  data as byte array."
  [^BAMReader bam-reader refs]
  (let [rdr (.data-reader bam-reader)
        block-size (lsb/read-int rdr)]
    (when (< block-size fixed-block-size)
      (throw (Exception. (str "Invalid block size:" block-size))))
    {:size block-size
     :data (lsb/read-bytes rdr block-size)}))

(defn- read-coordinate-alignment-block
  "Reads an alignment block, returning a map including the block size, the data
  as byte array, rname, flag and pos."
  [^BAMReader bam-reader refs]
  (let [rdr (.data-reader bam-reader)
        block-size (lsb/read-int rdr)]
    (when (< block-size fixed-block-size)
      (throw (Exception. (str "Invalid block size: " block-size))))
    (let [data (lsb/read-bytes rdr block-size)
          bb (ByteBuffer/wrap data)
          ref-id (lsb/read-int bb)
          rname (or (ref-name refs ref-id) "*")
          pos (inc (lsb/read-int bb))
          _ (lsb/skip bb 6) ;; l_qname, MAPQ, bin, n_cigar_op
          flag (lsb/read-ushort bb)
          ;; l_seq, rnext, pnext, tlen, qname
          ]
      {:size block-size
       :data data
       :rname rname
       :pos pos
       :flag flag
       :ref-id ref-id})))

(defn- read-queryname-alignment-block
  "Reads an alignment block, returning a map including the block size, the data
  as byte array, qname and flag."
  [^BAMReader bam-reader refs]
  (let [rdr (.data-reader bam-reader)
        block-size (lsb/read-int rdr)]
    (when (< block-size fixed-block-size)
      (throw (Exception. (str "Invalid block size: " block-size))))
    (let [data (lsb/read-bytes rdr block-size)
          bb (ByteBuffer/wrap data)
          _ (lsb/skip bb 8) ;; ref-id, pos
          l-read-name (lsb/read-ubyte bb)
          _ (lsb/skip bb 5) ;; MAPQ, bin, n_cigar_op
          flag (lsb/read-ushort bb)
          _ (lsb/skip bb 16) ;; l_seq, rnext, pnext, tlen
          qname (lsb/read-string bb l-read-name)]
      {:size block-size
       :data data
       :qname qname
       :flag flag})))

(defn- read-pointer-alignment-block
  "Reads an alignment block, returning a map including the data as byte array
  and the file pointers. This function is for indexing a BAM file."
  [^BAMReader bam-reader _]
  (let [rdr (.data-reader bam-reader)
        pointer-beg (.getFilePointer ^BGZFInputStream (.reader bam-reader))
        block-size (lsb/read-int rdr)]
    (when (< block-size fixed-block-size)
      (throw (Exception. (str "Invalid block size: " block-size))))
    {:data (lsb/read-bytes rdr block-size)
     :pointer-beg pointer-beg
     :pointer-end (.getFilePointer ^BGZFInputStream (.reader bam-reader))}))

;; Reading a single alignment
;; --------------------------

(defn- read-alignment
  "Reads a single alignment and decodes it entirely, returning a map including
  full information. See also `cljam.bam.decoder/deep-decode-alignment-block`."
  [bam-reader refs]
  (-> (read-alignment-block bam-reader refs)
      (decoder/deep-decode-alignment-block refs)))

(defn- light-read-alignment
  "Reads a single alignment and decodes it partialy, returning a map including
  partial information. See also
 `cljam.bam.decoder/light-decode-alignment-block`."
  [bam-reader refs]
  (-> (read-alignment-block bam-reader refs)
      (decoder/light-decode-alignment-block refs)))

(defn- pointer-read-alignment
  "Reads a single alignment and decodes it partialy, returning a map including
  partial information and file pointers. See also
 `cljam.bam.decoder/pointer-decode-alignment-block`."
  [bam-reader refs]
  (-> (read-pointer-alignment-block bam-reader refs)
      (decoder/pointer-decode-alignment-block refs)))

;;

(defn- read-to-finish
  [^BAMReader rdr
   ^Long start
   ^Long finish
   ^clojure.lang.IFn read-fn]
  (let [r ^BGZFInputStream (.reader rdr)]
    (when (< start finish)
      (.seek r start)
      (when-not (zero? (.available r))
        (let [result (read-fn rdr (.refs rdr))
              curr (.getFilePointer r)]
          (cons result
                (lazy-seq (read-to-finish rdr curr finish read-fn))))))))

(defn- read-alignments-first-only
  "It should be equivalent to [(first (filter window @candidates))]"
  [^BAMReader rdr spans window read-fn]
  (loop [left-spans spans]
    (when-let [span (first left-spans)]
      (let [[^Long begin ^Long finish] span]
        (or
          (loop [left (read-to-finish rdr begin finish read-fn)]
            (when-let [one (first left)]
              (if (window one)
                [one]
                (recur (rest left)))))
          (recur (rest left-spans)))))))

(defn- read-alignments*
  [^BAMReader rdr
   ^String chr ^Long start ^Long end
   deep-or-shallow]
  (when (nil? @(.index-delay rdr))
    (throw (Exception. "BAM index not found")))
  (let [bai @(.index-delay rdr)
        spans (if (= chr "*")
                (bai/get-unplaced-spans bai)
                (get-spans bai (ref-id (.refs rdr) chr) start end))
        window (fn [a]
                 (let [left ^long (:pos a)
                       right ^long (or (:end a) (get-end a))]
                   (and (= chr (:rname a))
                        (if (= chr "*")
                          true
                          (and
                           (<= start right)
                           (>= end left))))))
        read-fn (case deep-or-shallow
                  :first-only light-read-alignment
                  :shallow light-read-alignment
                  :deep read-alignment
                  :pointer pointer-read-alignment)
        candidates (flatten (keep (fn [[^long begin ^long finish]]
                                   (read-to-finish rdr begin finish read-fn)) spans))]
    (if (= deep-or-shallow :first-only)
      (read-alignments-first-only rdr spans window read-fn)
      (filter window candidates))))

(defn- read-alignments-sequentially*
  [^BAMReader rdr deep-or-shallow]
  (let [read-aln-fn (case deep-or-shallow
                      :shallow light-read-alignment
                      :deep read-alignment
                      :pointer pointer-read-alignment)
        read-fn (fn read-fn*
                  [^BAMReader r ^clojure.lang.PersistentVector refs]
                  (if-let [a (try (read-aln-fn r refs)
                                  (catch EOFException e nil))]
                    (cons a (lazy-seq (read-fn* r refs)))))]
    (read-fn rdr (.refs rdr))))

(defn- read-blocks-sequentially*
  [^BAMReader rdr mode]
  (let [read-block-fn (case mode
                        :normal read-alignment-block
                        :coordinate read-coordinate-alignment-block
                        :queryname read-queryname-alignment-block
                        :pointer read-pointer-alignment-block)
        read-fn (fn read-fn* [^BAMReader r ^clojure.lang.PersistentVector refs]
                  (if-let [b (try (read-block-fn r refs)
                                  (catch EOFException e nil))]
                    (cons b (lazy-seq (read-fn* r refs)))))]
    (read-fn rdr (.refs rdr))))

(defn- read-blocks*
  [^BAMReader rdr
   ^String chr ^Long start ^Long end]
  (when (nil? @(.index-delay rdr))
    (throw (Exception. "BAM index not found")))
  (let [bai @(.index-delay rdr)
        spans (get-spans bai (ref-id (.refs rdr) chr) start end)
        window (fn [^clojure.lang.PersistentHashMap a]
                 (let [^Long left (:pos a)]
                   (and (= chr (:rname a))
                        (<= start left end))))
        candidates (flatten (map (fn [[^Long begin ^Long finish]]
                                   (read-to-finish rdr begin finish read-coordinate-alignment-block)) spans))]
    (filter window candidates)))

(defn load-headers
  [rdr]
  (let [header (parse-header (lsb/read-string rdr (lsb/read-int rdr)))
        n-ref (lsb/read-int rdr)
        refs (loop [i n-ref, ret []]
               (if (zero? i)
                 ret
                 (let [l-name (lsb/read-int rdr)
                       name   (lsb/read-string rdr l-name)
                       l-ref  (lsb/read-int rdr)]
                   (recur (dec i)
                          (conj ret {:name (subs name 0 (dec l-name))
                                     :len  l-ref})))))]
    {:header header
     :refs refs}))