(ns bundle-test
  (:use [osc] :reload)
  (:use [clojure.test])
  (:import (java.nio ByteBuffer)))

(def HOST "127.0.0.1")
(def PORT 5432)

(declare compare-packets)

(defn compare-msgs [a b]
  (is (= (:path a) (:path b)))
  (is (= (count (:args a)) (count (:args b))))
  (doseq [pair (map vector  (:args a) (:args b))]
    (is (= (first pair) (second pair)))))

(defn compare-bundles [a b]
  (is (= (:timestamp a) (:timestamp b)))
  (is (= (count (:items a)) (count (:items b))))
  (doseq [packets (map vector (:items a) (:items b))]
    (apply compare-packets packets)))

(defn compare-packets [a b]
  (if (osc-bundle? a) (compare-bundles a b) (compare-msgs a b)))

(defn encode-decode-compare [packet]
  (let [buf (ByteBuffer/allocate 256)
        _ (osc-encode-packet buf packet)
        _ (.limit buf (.position buf))
        _ (.position buf 0)]
    (compare-packets packet (osc-decode-packet buf))))

(deftest encode-decode-empty []
         (encode-decode-compare (mk-osc-bundle OSC-TIMETAG-NOW [])))

(deftest encode-decode-one []
         (encode-decode-compare (mk-osc-bundle OSC-TIMETAG-NOW
                                            [(osc-msg-infer-types "/encode-decode-one"
                                                                   1 (float 11.0) "encode-decode--one")])))

(deftest encode-decode-two []
         (encode-decode-compare (mk-osc-bundle OSC-TIMETAG-NOW
                                            [(osc-msg-infer-types "/encode-decode-two"
                                                                   1 (float 11.0) "encode-decode-two")
                                             (osc-msg-infer-types "/encode-decode-two"
                                                                   2 (float 22.0) "encode-decode-two")])))

(deftest encode-decode-nested []
         (encode-decode-compare (mk-osc-bundle OSC-TIMETAG-NOW
                                            [(osc-msg-infer-types "/encode-decode"
                                                                   1 (float 11.0) "encode-decode")
                                             (mk-osc-bundle OSC-TIMETAG-NOW
                                                         [(osc-msg-infer-types "/encode-decode-nested"
                                                                               11 (float 111.0) "encode-decode-nested")])
                                             (osc-msg-infer-types "/encode-decode"
                                                                   2 (float 22.0) "encode-decode")])))

(deftest round-trip []
  (let [server (osc-server PORT)
        client (osc-client HOST PORT)
        bundle (mk-osc-bundle OSC-TIMETAG-NOW
                           [(osc-msg-infer-types "/round-trip/begin")
                            (osc-msg-infer-types "/round-trip/data" 1 (float 11.0) "round-trip-data")
                            (osc-msg-infer-types "/round-trip/end")])
        recv-msg (atom nil)]
    (try
      (osc-handle server "/round-trip/data" (fn [msg] (reset! recv-msg msg)))
      (osc-send-bundle client bundle)
      (compare-packets (last (:items bundle)) (osc-recv server "/round-trip/end" 600))
      (compare-packets (second (:items bundle)) @recv-msg)
      (finally
        (osc-close server true)
        (osc-close client true)))))

(deftest round-trip-bundle-macro []
  (let [server (osc-server PORT)
        client (osc-client HOST PORT)
        args [1 (float 11.0) "round-trip-data"]
        recv-msg (atom nil)]
    (try
      (osc-handle server "/round-trip/data" (fn [msg] (reset! recv-msg msg)))
      (in-osc-bundle client OSC-TIMETAG-NOW
                     (osc-send client "/round-trip/begin")
                     (apply osc-send client "/round-trip/data" args)
                     (osc-send client "/round-trip/end"))
      (is (= "/round-trip/end" (:path (osc-recv server "/round-trip/end" 600))))
      (is (= (count args) (count (:args @recv-msg))))
      (doseq [pair (map vector args (:args @recv-msg))]
        (is (= (first pair) (second pair))))
      (finally
        (osc-close server true)
        (osc-close client true)))))
