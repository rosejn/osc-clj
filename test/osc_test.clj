(ns osc-test
  (:use osc
     clojure.test)
  (:import (java.nio ByteBuffer)))

(def HOST "127.0.0.1")
(def PORT (+ 1000 (rand-int 10000)))

(def STR "test-string")

(deftest osc-msg-test []
  (let [buf (ByteBuffer/allocate 128)
        t-args [(make-array Byte/TYPE 20)
              42
              (float 4.2)
              "qwerasdf"
              (double 123.23)
              (long 123412341234)]
        _ (osc-encode-msg buf (apply osc-msg "/asdf" "bifsdh" t-args))
        _ (.position buf 0)
        {:keys [path args] :as msg} (osc-decode-packet buf)
        ]
    (is (= "/asdf" path))
    (is (= (count t-args) (count args)))
    (is (= (ffirst t-args) (ffirst args)))
    (is (= (last (first t-args)) (last (first args))))
    (is (= (next t-args) (next args)))))

(deftest thread-lifetime-test []
  (let [server (osc-server PORT)
        client (osc-client HOST PORT)]
    (osc-close client 100)
    (osc-close server 100)
    (is (= false (.isAlive (:send-thread server))))
    (is (= false (.isAlive (:listen-thread server))))
    (is (= false (.isAlive (:send-thread client))))
    (is (= false (.isAlive (:listen-thread client))))))

(defn check-msg [msg path & args]
  (is (not (nil? msg)))
  (let [m-args (:args msg)]
    (is (= (:path msg) path))
    (doseq [i (range (count args))]
      (is (= (nth m-args i) (nth args i))))))

(deftest osc-basic-test []
  (is (= 1 1))
  (let [server (osc-server PORT)
        client (osc-client HOST PORT)
        counter (atom 0)]
    (try
      (osc-handle server "/test" (fn [msg] (swap! counter + (first (:args msg)))))
      (dotimes [i 100] (osc-send client "/test" 1))
      (Thread/sleep 400)
      (is (= 100 @counter))

      (osc-send client "/foo" 42)
      (check-msg (osc-recv server "/foo" 600) "/foo" 42)
      (is (nil? (osc-recv server "/foo" 0)))
      (finally
        (osc-close server true)
        (osc-close client true)))))

(deftest osc-slowpoke-server-test []
  (let [server (osc-server PORT)
        client (osc-client HOST PORT)
        coll (atom [])
        end 50
        end-millis (+ (System/currentTimeMillis) (* end 1000 1/5))]
    (try
      (osc-handle server "/test" (fn [msg] (swap! coll (fn [s]
                                                        (Thread/sleep 100)
                                                        (conj s (first (:args msg)))))))
      (dotimes [i end] (osc-send client "/test" i))
      (while (or (not= (count @coll) end) (< (System/currentTimeMillis) end-millis))
        (Thread/sleep 250))
      (dorun (map-indexed #(is (= %1 %2)) @coll))
      (finally
        (osc-close server true)
        (osc-close client true)))))


(defn osc-tests []
  (binding [*test-out* *out*]
    (run-tests 'osc-test)))

(defn test-ns-hook []
  (osc-msg-test)
  (thread-lifetime-test)
  (osc-basic-test)
  (osc-slowpoke-server-test))
