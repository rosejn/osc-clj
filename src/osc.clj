(ns osc
  (:import
     (java.util.concurrent TimeUnit TimeoutException PriorityBlockingQueue)
     (java.net InetSocketAddress DatagramSocket DatagramPacket)
     (java.nio.channels DatagramChannel AsynchronousCloseException ClosedChannelException)
     (java.nio ByteBuffer ByteOrder))
  (:use clojure.set
        [clojure.contrib.fcase :only (instance-case)]))

; TODO: Look into using nano-seconds instead of millis
(defn osc-now []
  (System/currentTimeMillis))

(defn osc-msg
  "Returns an OSC message."
  [path & args]
  (let [type-tag (first args)
        type-tag (if (and type-tag (.startsWith type-tag ","))
                   (.substring type-tag 1)
                   type-tag)]
    (with-meta {:path path
                :type-tag type-tag
                :args (next args)}
               {:type :osc-msg})))

(defn osc-msg?
  "Is obj an OSC message?"
  [obj] (= :osc-msg (type obj)))

(defn osc-bundle
  "Returns an OSC bundle, which is a timestamped set of OSC messages and/or bundles."
  [timestamp items]
  (with-meta {:timestamp timestamp
              :items items}
             {:type :osc-bundle}))

(defn osc-bundle? [obj] (= :osc-bundle (type obj)))

(load "osc/internals")

(defn osc-listen
  "Attach a generic listener function that will be called with every incoming osc message."
  [peer listener]
  (dosync
    (alter (:listeners peer) conj listener)))

; TODO: change this around so returning :done will result in removal of a handler, and make
; this function accept the handler to remove as an argument.
(defn osc-remove-handler
  "Called from within a handler to remove itself."
  []
  (dosync (alter *osc-handlers* assoc *current-path*
                 (difference (get @*osc-handlers* *current-path*) #{*current-handler*}))))

(defn osc-handle
  "Attach a handler function to receive on the specified path.  (Works for both clients and servers.)

  (let [server (osc-server PORT)
        client (osc-client HOST PORT)
        flag (atom false)]
    (try
      (osc-handle server \"/test\" (fn [msg] (reset! flag true)))
      (osc-send client \"/test\" \"i\" 42)
      (Thread/sleep 200)
      (= true @flag)))

"
  [peer path handler & [one-shot]]
  (let [handlers (:handlers peer)
        phandlers (get @handlers path #{})
        handler (if one-shot
                  (fn [msg]
                    (handler msg)
                    (osc-remove-handler))
                  handler)]
    (dosync (alter handlers assoc path (union phandlers #{handler}))))) ; save the handler

(defn osc-recv
  "Receive a single message on an osc path (node) with an optional timeout.

      ; Wait a max of 250 ms to receive the next incoming OSC message
      ; addressed to the /magic node.
      (osc-recv client \"/magic\" 250)
  "
  [peer path & [timeout]]
  (let [p (promise)]
    (osc-handle peer path (fn [msg]
                           (deliver p msg)
                            (osc-remove-handler)))
    (let [res (try
                (if timeout
                  (.get (future @p) timeout TimeUnit/MILLISECONDS) ; Blocks until
                  @p)
                (catch TimeoutException t
                  nil))]
      res)))

;; We use binding to *osc-msg-bundle* to bundle messages
;; and send combined with an OSC timestamp.
(def *osc-msg-bundle* nil)

(defn osc-send-msg
  "Send OSC msg to peer."
  [peer msg]
  (if @osc-debug*
    (print-debug "osc-send-msg: " msg))
  (if *osc-msg-bundle*
    (swap! *osc-msg-bundle* #(conj %1 msg))
    (.put (:send-q peer) [peer msg])))

(defn osc-send-bundle
  "Send OSC bundle to peer."
  [peer bundle]
  (if @osc-debug*
    (print-debug "osc-send-msg: " bundle))
  (.put (:send-q peer) [peer bundle]))

(defn osc-send
  "Creates an OSC message and either sends it to the server immediately
  or if a bundle is currently being formed it adds it to the list of messages."
  [client path & args]
  (osc-send-msg client (apply osc-msg path (osc-type-tag args) args)))

(defmacro in-osc-bundle [client timestamp & body]
  `(binding [*osc-msg-bundle* (atom [])]
     (let [res# ~@body]
       (osc-send-bundle ~client (osc-bundle ~timestamp @*osc-msg-bundle*))
       res#)))

; OSC peers have listeners and handlers.  A listener is sent every message received, and
; handlers are dispatched by OSC node (a.k.a. path).

(defn osc-client
 "Returns an OSC client ready to communicate with a host on a given port.
 Use :protocol in the options map to \"tcp\" if you don't want \"udp\"."
  [host port]
  (let [peer (osc-peer true)
        sock (.socket (:chan peer))
        local (.getLocalPort sock)]
    (.bind sock (InetSocketAddress. local))
    (assoc peer
           :host (ref host)
           :port (ref port)
           :addr (ref (InetSocketAddress. host port)))))

(defn osc-target
  "Update the target address of an OSC client so future calls to osc-send
  will go to a new destination."
  [peer host port]
  (dosync
    (ref-set (:host peer) host)
    (ref-set (:port peer) port)
    (ref-set (:addr peer) (InetSocketAddress. host port))))

(defn osc-server
  "Returns a live OSC server ready to register handler functions."
  [port]
  (let [peer (osc-peer true)
        sock (.socket (:chan peer))]
    (.bind sock (InetSocketAddress. port))
    (assoc peer
           :host (ref nil)
           :port (ref port)
           :addr (ref nil))))

(defn osc-close
  [peer & wait]
  (dosync (ref-set (:running? peer) false))
  (.close (:chan peer))
  (when wait 
    (if (:listen-thread peer)
      (.join (:listen-thread peer)))
    (if (:send-thread peer)
      (.join (:send-thread peer)))))

(defn osc-debug
  [& [on-off]]
  (let [on-off (if (= on-off false) false true)]
    (dosync (ref-set osc-debug* on-off))))
