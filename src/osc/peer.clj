(ns osc.peer
  (:import
   (java.net InetSocketAddress DatagramSocket DatagramPacket)
   (java.util.concurrent TimeUnit TimeoutException PriorityBlockingQueue)
   (java.nio.channels DatagramChannel AsynchronousCloseException ClosedChannelException)
   (java.nio ByteBuffer)
   (javax.jmdns JmDNS ServiceListener ServiceInfo))
  (:use [clojure.set :as set]
        [osc.util]
        [osc.decode :only [osc-decode-packet]]
        [osc.encode :only [osc-encode-msg osc-encode-bundle]]))


(defonce ZERO-CONF (JmDNS/create))

(defn peer-unregister-all-zero-conf-services
  []
  (.unregisterAllServices ZERO-CONF))

(defonce __clean-slate__ (peer-unregister-all-zero-conf-services))

(defn- recv-next-packet
  "blocks on .receive if nothing to receive"
  [chan buf]
  (.clear buf)
  (let [src-addr (.receive chan buf)]
    (when (pos? (.position buf))
      (.flip buf)
      [src-addr (osc-decode-packet buf)])))

(defn- send-loop [running? send-q send-buf chan]
  (while @running?
    (if-let [res (.poll send-q
                        SEND-LOOP-TIMEOUT
                        TimeUnit/MILLISECONDS)]
      (let [[peer m] res]
        (cond
          (osc-msg? m) (osc-encode-msg send-buf m)
          (osc-bundle? m) (osc-encode-bundle send-buf m))
        (.flip send-buf)
        (try
          ((:send-fn peer) peer send-buf)
          (catch Exception e
            (print-debug "Exception in send-loop: " e  "\nstacktrace: "
                         (.printStackTrace e))
            (throw e)))

        (.clear send-buf))) ; clear resets everything
    ))

(defn- handle-msg [all-listeners src msg]
  (let [msg              (assoc msg
                           :src-host (.getHostName src)
                           :src-port (.getPort src))
        listeners        (vals @(:listeners all-listeners))
        default-listener (:default all-listeners)]
    (doseq [listener (conj listeners default-listener)]
      (try
        (listener msg)
        (catch Exception e
          (print-debug "Listener Exception. Got msg - " msg "\n"
                   (with-out-str (.printStackTrace e))))))))

(defn- handle-bundle [all-listeners src bundle]
  (doseq [item (:items bundle)]
    (if (osc-msg? item)
      (handle-msg all-listeners src item)
      (handle-bundle all-listeners src item))))

(defn- listen-loop [chan buf running? all-listeners]
  (try
    (while @running?
      (try
        (let [[src pkt] (recv-next-packet chan buf)]
          (cond
            (osc-bundle? pkt) (handle-bundle all-listeners src pkt)
            (osc-msg? pkt)    (handle-msg all-listeners src pkt)))
        (catch AsynchronousCloseException e
          (if @running?
            (do
              (print-debug "AsynchronousCloseException in OSC listen-loop...")
              (print-debug (.printStackTrace e)))))
        (catch ClosedChannelException e
          (if @running?
            (do
              (print-debug "ClosedChannelException in OSC listen-loop...")
              (print-debug (.printStackTrace e)))))
        (catch Exception e
          (print-debug "Exception in listen-loop: " e " \nstacktrace: "
                       (.printStackTrace e))
          (throw e))))
  (finally
    (if (.isOpen chan)
      (.close chan)))))

(defn- remove-handler
  "Remove the supplied handler from the specified path within handlers. If no
  key is passed, key defaults to the handler object itself."
  [handlers path key]
  (let [path-parts (split-path path)
        phandlers   (:handlers (get-in @handlers path-parts {:handlers {}}))]
    (dosync (alter handlers assoc-in path-parts {:handlers (dissoc phandlers key)}))))

;;TODO  This needs to grab *more* handlers depending on the wildcards in the incoming msg
(defn- mk-default-listener
  "Return a fn which dispatches the passed in message to all specified handlers with
  a matching path."
  [handlers]
  (fn [msg]
    (let [hs         @handlers
          path       (:path msg)
          path-parts (split-path path)]
      (doseq [[key handler]  (:handlers (get-in hs path-parts {:handlers {}}))]
        (let [res (try
                    (handler msg)
                    (catch Exception e
                      (print-debug "Handler Exception. Got msg - " msg "\n"
                                   (with-out-str (.printStackTrace e)))))]
          (when (= :done res)
            (remove-handler handlers path key)))))))

(defn- listener-thread [chan buf running? all-listeners]
  (let [t (Thread. #(listen-loop chan buf running? all-listeners))]
    (.start t)
    t))

(defn- sender-thread [& args]
  (let [t (Thread. #(apply send-loop args))]
    (.start t)
    t))

(defn- chan-send [peer send-buf]
  (let [{:keys [chan addr]} peer]
    (.send chan send-buf @addr)))

; OSC peers have listeners and handlers.  A listener is sent every message received, and
; handlers are dispatched by OSC node (a.k.a. path).
(defn osc-peer [& [listen?]]
  (let [chan (DatagramChannel/open)
        rcv-buf (ByteBuffer/allocate BUFFER-SIZE)
        send-buf (ByteBuffer/allocate BUFFER-SIZE)
        send-q (PriorityBlockingQueue. OSC-SEND-Q-SIZE (comparator (fn [a b] (< (:timestamp (second a)) (:timestamp (second b))))))
        running? (ref true)
        handlers (ref {})
        default-listener (mk-default-listener handlers)
        listeners (ref {})
        send-thread (sender-thread running? send-q send-buf chan)
        listen-thread (when listen?
                        (listener-thread chan rcv-buf running? {:listeners listeners
                                                                :default default-listener}))]
    (.configureBlocking chan true)
    {:chan chan
     :rcv-buf rcv-buf
     :send-q send-q
     :running? running?
     :send-thread send-thread
     :listen-thread listen-thread
     :default-listener default-listener
     :listeners listeners
     :handlers handlers
     :send-fn chan-send}))

(defn client-peer
 "Returns an OSC client ready to communicate with a host on a given port.
 Use :protocol in the options map to \"tcp\" if you don't want \"udp\"."
  [host port]
  (let [peer (osc-peer :with-listener)
        sock (.socket (:chan peer))
        local (.getLocalPort sock)]
    (.bind sock (InetSocketAddress. local))
    (assoc peer
           :host (ref host)
           :port (ref port)
           :addr (ref (InetSocketAddress. host port)))))

(defn unregister-with-zero-conf
  [peer]
  (when-let [zero-service @(:zero-service peer)]
    (.unregisterService ZERO-CONF zero-service)
    (dosync
     (ref-set (:zero-service peer) nil))))

(defn register-with-zero-conf
  [peer]
  (let [port @(:port peer)
        zero-name    (str (:zero-conf-name peer) " : " port)
        zero-service (ServiceInfo/create "_osc._udp.local" zero-name port "Overtone OSC Server")]
    (.registerService ZERO-CONF zero-service)
    (dosync
     (ref-set (:zero-service peer) zero-service))))

(defn update-peer-target
  "Update the target address of an OSC client so future calls to osc-send
  will go to a new destination."
  [peer host port]

  (when (:use-zero-conf? peer)
    (unregister-with-zero-conf peer))

  (dosync
    (ref-set (:host peer) host)
    (ref-set (:port peer) port)
    (ref-set (:addr peer) (InetSocketAddress. host port)))

  (when (:use-zero-conf? peer)
    (register-with-zero-conf peer)))

(defn server-peer
  "Returns a live OSC server ready to register handler functions."
  [port zero-conf-name]
  (let [peer (osc-peer :with-listener)
        sock (.socket (:chan peer))
        zero-conf? (not (or
                         (nil? zero-conf-name)
                         (false? zero-conf-name)))]

    (.bind sock (InetSocketAddress. port))

    (let [peer (assoc peer
                 :host (ref nil)
                 :port (ref port)
                 :addr (ref nil)
                 :zero-conf-name zero-conf-name
                 :zero-service (ref nil)
                 :use-zero-conf? zero-conf?)]
      (when zero-conf?
        (register-with-zero-conf peer))
      peer)))

(defn close-peer
  "Close an osc-peer, also works for clients and servers."
  [peer & wait]
  (when (:use-zero-conf? peer)
    (unregister-with-zero-conf peer))
  (dosync (ref-set (:running? peer) false))
  (.close (:chan peer))
  (when wait
    (if (:listen-thread peer)
      (if (integer? wait)
        (.join (:listen-thread peer) wait)
        (.join (:listen-thread peer))))
    (if (:send-thread peer)
      (if (integer? wait)
        (.join (:send-thread peer) wait)
        (.join (:send-thread peer))))))


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
    (.put (:send-q peer) [peer (assoc msg :timestamp 0)])))

(defn osc-send-bundle
  "Send OSC bundle to peer."
  [peer bundle]
  (if @osc-debug*
    (print-debug "osc-send-bundle: " bundle))
  (.put (:send-q peer) [peer bundle]))

(defmacro in-osc-bundle [client timestamp & body]
  `(binding [*osc-msg-bundle* (atom [])]
     (let [res# (do ~@body)]
       (osc-send-bundle ~client (osc-bundle ~timestamp @*osc-msg-bundle*))
       res#)))

(defn peer-handle
  [peer path handler key]
  (when (contains-illegal-chars? path)
    (throw (IllegalArgumentException. (str "OSC handle paths may not contain the following chars: " ILLEGAL-METHOD-CHARS))))
  (when (.endsWith path "/")
    (throw (IllegalArgumentException. (str "OSC handle needs a method name (i.e. must not end with /)"))))
  (let [handlers (:handlers peer)
        path-parts (split-path path)

        phandlers (:handlers (get-in @handlers path-parts {:handlers {}}))]
    (dosync (alter handlers assoc-in (conj (vec path-parts) :handlers) (assoc phandlers key handler)))))

(defn peer-recv
  [peer path timeout]
  (let [p (promise)]
    (peer-handle peer path (fn [msg]
                            (deliver p msg)
                            :done))
    (let [res (try
                (if timeout
                  (.get (future @p) timeout TimeUnit/MILLISECONDS) ; Blocks until
                  @p)
                (catch TimeoutException t
                  nil))]
      res)))

(defn peer-rm-handlers
  [peer path]
  (let [handlers (:handlers peer)
        path-parts (split-path path)]
    (dosync
     (alter handlers assoc-in (conj (vec path-parts) :handlers) {}))))

(defn peer-rm-all-handlers
  "remove all handlers recursively down from path"
  ([peer path]
     (let [handlers (:handlers peer)
           path-parts (split-path path)]
       (dosync
        (if (empty? path-parts)
          (ref-set handlers {})
          (alter  handlers path-parts {}))))))

(defn peer-rm-handler
  "remove handler with specific key associated with path"
  [peer path key]
  (let [handlers (:handlers peer)]
    (remove-handler handlers path key)))
