(ns cljam.io.bam-index-test
  "Tests for cljam.io.bam-index."
  (:require [clojure.java.io :as cio]
            [clojure.test :refer :all]
            [cljam.test-common :refer :all]
            [cljam.io.bam-index :as bai]))

;;; bin-index

(deftest bin-index-is-done-without-errors
  (is (not-throw? (bai/bin-index test-bai-file 0)))
  (is (not-throw? (bai/bin-index test-bai-file 1))))
(deftest bin-index-throws-BoundsException-for-the-invalid-given-index
  (is (thrown? Exception (bai/bin-index test-bai-file 2))))
(deftest bin-index-returns-vector
  (is (vector? (bai/bin-index test-bai-file 0))))
(deftest bin-index-check-the-returning-vectors-structure
  (is (every? (partial just-map?
                       {:bin number?
                        :chunks #(every? (partial just-map?
                                                  {:beg number?
                                                   :end number?}) %)})
              (bai/bin-index test-bai-file 0))))

;;; linear-index

(deftest linear-index-is-done-without-errors
  (is (not-throw? (bai/linear-index test-bai-file 0))))
(deftest linear-index-throws-Exception-for-the-invalid-given-index
  (is (thrown? Exception (bai/linear-index test-bai-file 2))))
(deftest linear-index-returns-vector
  (is (vector? (bai/linear-index test-bai-file 0))))
(deftest linear-index-check-the-returning-vectors-structure
  (is (every? number? (bai/linear-index test-bai-file 0))))

;;; get-spans

(deftest get-spans-returns-a-sequence-including-regions
  (let [bai* (bai/bam-index test-bai-file)]
    (is (seq? (bai/get-spans bai* 0 0 100)))
    (is (every? #(and (= 2 (count %))
                      (number? (first %))
                      (number? (second %)))
                (bai/get-spans bai* 0 0 100)))))
(deftest get-spans-returns-correct-regions
  (let [bai* (bai/bam-index test-bai-file)]
    (is (= (bai/get-spans bai* 0 0 100) '((97 555))))
    (is (= (bai/get-spans bai* 1 0 100) '((555 29163520))))))

;;; Tests for a large-size file.

(deftest-remote bin-index-is-done-without-errors-with-a-large-file
  (with-before-after {:before (prepare-cavia!)}
    (is (not-throw? (bai/bin-index test-large-bai-file 0)))))
(deftest-remote linear-index-is-done-without-errors-with-a-large-file
  (with-before-after {:before (prepare-cavia!)}
    (is (not-throw? (bai/linear-index test-large-bai-file 0)))))

(deftest source-type-test
  (with-open [server (http-server)]
    (are [x] (and (vector? (bai/bin-index x 0))
                  (vector? (bai/linear-index x 0))
                  (= (bai/get-spans (bai/bam-index x) 0 0 100) '((97 555))))
      test-bai-file
      (cio/file test-bai-file)
      (cio/as-url (cio/file test-bai-file))
      (cio/as-url (str (:uri server) "/bam/test.sorted.bam.bai")))))
