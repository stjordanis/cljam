(ns cljam.io.sam.util.cigar
  "Parser of CIGAR strings."
  (:import [java.nio ByteBuffer ByteOrder]))

(defn parse
  "Parses CIGAR string, returning a sequence of lengths and operations."
  [^String s]
  (for [[_ n op] (re-seq #"([0-9]*)([MIDNSHP=X])" s)]
    [(Integer/parseInt n) (first op)]))

(defn simplify
  "Merge contiguous same operations of parsed CIGAR."
  [cigs]
  (loop [[[^long l op :as x] & xs] cigs result (transient [])]
    (if (and l op)
      (let [[^long nl nop] (first xs)]
        (if (= op nop)
          (recur (cons [(+ l nl) op] (next xs)) result)
          (recur xs (conj! result x))))
      (persistent! result))))

(defn- concat! [v coll]
  (reduce conj! v coll))

(defn- update-last! [coll f]
  (let [c (dec (count coll))]
    (if (neg? c)
      coll
      (let [[op x] (get coll c)]
        (if (= :m op)
          (assoc! coll c (f x))
          coll)))))

(defn to-index*
  "Convert CIGAR string to sequence of indices."
  [^String s]
  (let [cigs (simplify (remove (comp #{\P \H} second) (parse s)))]
    (loop [[[^long l op] & xs] cigs r 0 s 0 idx (transient [])]
      (if (and l op)
        (condp get op
          #{\M \= \X} (recur xs (+ l r) (+ l s) (concat! idx (map (fn [x] [:m x]) (range s (+ l s)))))
          #{\D} (recur xs (+ r l) s (concat! (update-last! idx (fn [x] [:d x l])) (repeat l [:m \*])))
          #{\N} (recur xs (+ r l) s (concat! idx (repeat l [:m \>])))
          #{\S} (recur xs r (+ s l) idx)
          #{\I} (recur xs r (+ s l) (update-last! idx (fn [x] [:i x [s (+ l s)]]))))
        (persistent! idx)))))

(def to-index (memoize to-index*))

(defn count-op
  "Returns length of CIGAR operations."
  [^String s]
  (count (parse s)))

(defn- count-ref-str*
  [^String s]
  (->> (parse s)
       (filter (comp #{\M \D \N \= \X} peek))
       (map first)
       (reduce +)))

(def ^:private count-ref-str
  (memoize count-ref-str*))

(defn count-ref-bytes
  "Count covering length in reference from encoded CIGAR byte-array."
  [cigar-bytes]
  (let [buf (ByteBuffer/wrap cigar-bytes)]
    (.order buf ByteOrder/LITTLE_ENDIAN)
    (loop [ref-length 0]
      (if (.hasRemaining buf)
        (let [b (.getInt buf)
              op (bit-and b 0xF)
              n (bit-shift-right b 4)]
          (recur (+ ref-length (case op 0 n 2 n 3 n 7 n 8 n 0))))
        ref-length))))

(defn decode-cigar-and-ref-length
  "Decode CIGAR string and length of alignment in reference.
  Returns a vector of [cigar, ref-length]."
  [cigar-bytes]
  (let [buf (ByteBuffer/wrap cigar-bytes)
        sb (StringBuilder.)]
    (.order buf ByteOrder/LITTLE_ENDIAN)
    (loop [ref-length 0]
      (if (.hasRemaining buf)
        (let [b (.getInt buf)
              op (bit-and b 0xF)
              n  (bit-shift-right b 4)]
          (doto sb
            (.append n)
            (.append (case op 0 \M 1 \I 2 \D 3 \N 4 \S 5 \H 6 \P 7 \= 8 \X)))
          (recur (+ ref-length (case op 0 n 2 n 3 n 7 n 8 n 0))))
        [(.toString sb) ref-length]))))

(defn encode-cigar
  "Encodes CIGAR string into a sequence of longs."
  [cigar]
  (mapv #(bit-or (bit-shift-left (first %) 4)
                 (case (second %) \M 0 \I 1 \D 2 \N 3 \S 4 \H 5 \P 6 \= 7 \X 8))
        (parse cigar)))

(defmulti count-ref
  "Returns length of reference bases."
  class)

(defmethod count-ref String
  [s]
  (count-ref-str s))

(defmethod count-ref (Class/forName "[B")
  [b]
  (count-ref-bytes b))
