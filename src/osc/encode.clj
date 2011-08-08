(ns osc.encode
  (use [osc.util]))

(defn osc-pad
  "Add 0-3 null bytes to make buffer position 32-bit aligned."
  [buf]
  (let [extra (mod (.position buf) 4)]
    (if (pos? extra)
      (.put buf PAD 0 (- 4 extra)))))

(defn encode-string [buf s]
  (.put buf (.getBytes s))
  (.put buf (byte 0))
  (osc-pad buf))

(defn encode-blob [buf b]
  (.putInt buf (count b))
  (.put buf b)
  (osc-pad buf))

(defn encode-timetag
  ([buf] (encode-timetag buf (osc-now)))
  ([buf timestamp]
     (if (= timestamp OSC-TIMETAG-NOW)
       (doto buf (.putInt 0) (.putInt 1))
       (let [secs (+ (/ timestamp 1000) ; secs since Jan. 1, 1970
                     SEVENTY-YEAR-SECS) ; to Jan. 1, 1900
             fracs (/ (bit-shift-left (long (mod timestamp 1000)) 32)
                      1000)
             tag (bit-or (bit-shift-left (long secs) 32) (long fracs))]
         (.putLong buf (long tag))))))

(defn osc-encode-msg [buf msg]
  (let [{:keys [path type-tag args]} msg]
    (encode-string buf path)
    (encode-string buf (str "," type-tag))
    (doseq [[t arg] (map vector type-tag args)]
      (case t
            \i (.putInt buf (int arg))
            \h (.putLong buf (long arg))
            \f (.putFloat buf (float arg))
            \d (.putDouble buf (double arg))
            \b (encode-blob buf arg)
            \s (encode-string buf arg))
      ))
  buf)

(declare osc-encode-packet)

(defn osc-encode-bundle [buf bundle]
  (encode-string buf "#bundle")
  (encode-timetag buf (:timestamp bundle))
  (doseq [item (:items bundle)]
    ; A bit of a hack...
    ; Write an empty bundle element size into the buffer, then encode
    ; the actual bundle element, and then go back and write the correct
    ; size based on the new buffer position.
    (let [start-pos (.position buf)]
      (.putInt buf (int 0))
      (osc-encode-packet buf item)
      (let [end-pos (.position buf)]
        (.position buf start-pos)
        (.putInt buf (- end-pos start-pos 4))
        (.position buf end-pos))))
  buf)

(defn osc-encode-packet [buf packet]
  (if (osc-msg? packet) (osc-encode-msg buf packet) (osc-encode-bundle buf packet)))
