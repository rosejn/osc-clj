(ns osc.decode
  (:use [osc.util]))

(defn osc-align
  "Jump the current position to a 4 byte boundary for OSC compatible alignment."
  [buf]
  (.position buf (bit-and (bit-not 3) (+ 3 (.position buf)))))

(defn- decode-string [buf]
  (let [start (.position buf)]
    (while (not (zero? (.get buf))) nil)
    (let [end (.position buf)
          len (- end start)
          str-buf (byte-array len)]
      (.position buf start)
      (.get buf str-buf 0 len)
      (osc-align buf)
      (String. str-buf 0 (dec len)))))

(defn- decode-blob [buf]
  (let [size (.getInt buf)
        blob (byte-array size)]
    (.get buf blob 0 size)
    (osc-align buf)
    blob))

(defn- decode-msg
  "Pull data out of the message according to the type tag."
  [buf]
  (let [path (decode-string buf)
        type-tag (decode-string buf)
        args (reduce (fn [mem t]
                       (conj mem
                             (case t
                                   \i (.getInt buf)
                                   \h (.getLong buf)
                                   \f (.getFloat buf)
                                   \d (.getDouble buf)
                                   \b (decode-blob buf)
                                   \s (decode-string buf))))
                     []
                     (rest type-tag))]
    (apply osc-msg path type-tag args)))

(defn- decode-timetag [buf]
  (let [tag (.getLong buf)]
    (if (= tag OSC-TIMETAG-NOW)
      OSC-TIMETAG-NOW
      (let [secs (- (bit-shift-right tag 32) SEVENTY-YEAR-SECS)
            ms-frac (bit-shift-right (* (bit-and tag (bit-shift-left 0xffffffff 32))
                                        1000) 32)]
        (+ (* secs 1000)                ; secs as ms
           ms-frac)))))

(defn- osc-bundle-buf? [buf]
  (let [start-char (char (.get buf))]
    (.position buf (- (.position buf) 1))
    (= \# start-char)))

(declare osc-decode-packet)

(defn- decode-bundle-items [buf]
  (loop [items []]
    (if (.hasRemaining buf)
      (let [item-size (.getInt buf)
            original-limit (.limit buf)
            item (do (.limit buf (+ (.position buf) item-size)) (osc-decode-packet buf))]
        (.limit buf original-limit)
        (recur (conj items item)))
      items)))

(defn- decode-bundle [buf]
  (decode-string buf) ; #bundle
  (osc-bundle (decode-timetag buf) (decode-bundle-items buf)))

(defn osc-decode-packet
  "Decode an OSC packet buffer into a bundle or message map."
  [buf]
  (if (osc-bundle-buf? buf)
    (decode-bundle buf)
    (decode-msg buf)))
