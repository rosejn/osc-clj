(ns osc.peer
  (:import
   (java.net InetSocketAddress DatagramSocket DatagramPacket)
   (java.util.concurrent TimeUnit TimeoutException PriorityBlockingQueue)
   (java.nio.channels DatagramChannel AsynchronousCloseException ClosedChannelException)
   (java.nio ByteBuffer)
   (javax.jmdns JmDNS ServiceListener ServiceInfo))
  (:require [at-at :as at-at])
  (:use [clojure.set :as set]
        [osc.util]
        [osc.decode :only [osc-decode-packet]]
        [osc.encode :only [osc-encode-msg osc-encode-bundle]]))

(def zero-conf* (agent nil))
(def zero-conf-services* (atom {}))

(defn turn-zero-conf-on
  "Turn zeroconf on and register all services in zero-conf-services* if any."
  []
  (send zero-conf* (fn [zero-conf]
                     (if zero-conf
                       zero-conf
                       (let [zero-conf (JmDNS/create)]
                         (doseq [service (vals @zero-conf-services*)]
                           (.registerService zero-conf service))
                         zero-conf))))
  :zero-conf-on)

(defn turn-zero-conf-off
  "Unregister all zeroconf services and close zeroconf down."
  []
  (send zero-conf* (fn [zero-conf]
                     (when zero-conf
                       (.unregisterAllServices zero-conf)
                       (.close zero-conf))
                     nil))
  :zero-conf-off)

(defn unregister-zero-conf-service
  "Unregister zeroconf service registered with port."
  [port]
  (send zero-conf* (fn [zero-conf port]
                     (swap! zero-conf-services* dissoc port)
                     (let [service (get @zero-conf-services* port)]
                       (when (and zero-conf zero-conf)
                         (.unregisterService zero-conf service)))
                     zero-conf)
        port))

(defn register-zero-conf-service
  "Register zeroconf service with name service-name and port."
  [service-name port]
  (send zero-conf* (fn [zero-conf service-name port]
                     (let [service-name (str service-name " : " port)
                           service (ServiceInfo/create "_osc._udp.local"
                                                       service-name port
                                                       (str "Clojure OSC Server"))]
                       (swap! zero-conf-services* assoc port service)
                       (when zero-conf
                         (.registerService zero-conf service))
                       zero-conf))
        service-name
        port))

(defn zero-conf-running?
  []
  (if @zero-conf*
    true
    false))

(defn- recv-next-packet
  "Fills buf with the contents of the next packet and then decodes it into an
  OSC message map. Returns a vec of the source address of the packet and the
  message map itself. Blocks current thread if nothing to receive."
  [chan buf]
  (.clear buf)
  (let [src-addr (.receive chan buf)]
    (when (pos? (.position buf))
      (.flip buf)
      [src-addr (osc-decode-packet buf)])))

(defn- send-loop
  "Loop for the send thread to execute in order to send OSC messages externally.
  Reads messages from send-q, encodes them using send-buf and sends them out
  using the peer's send-fn extracted from send-q (send-q is expected to contain a
  sequence of [peer message]). "
  [running? send-q send-buf]
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

(defn- handle-msg
  "Send msg to all listeners. all-liseners is a map containing the keys
  :listeners (a ref of all user-registered listeners which may resolve to the
  empty list) and :default (the default listener). Each listener is then
  extracted and called with the message as a param. Before invoking the
  listeners the source host and port are added to the  message map."
  [all-listeners src msg]
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

(defn- handle-bundle
  "Extract all :items in the bundle and either handle the message if a normal
  OSC message, or handle bundle recursively. Schedule the bundle to be handled
  according to its timestamp."
  [all-listeners src bundle]
  (at-at/at (:timestamp bundle)
            #(doseq [item (:items bundle)]
               (if (osc-msg? item)
                 (handle-msg all-listeners src item)
                 (handle-bundle all-listeners src item)))))

(defn- listen-loop
  "Loop for the listen thread to execute in order to receive and handle OSC
  messages. Recieves packets from chan using buf and then handles them either
  as messages or bundles - passing the source information and message itself."
  [chan buf running? all-listeners]
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
                       (.printStackTrace e)))))
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

(defn- listener-thread
  "Thread which runs the listen-loop"
  [chan buf running? all-listeners]
  (let [t (Thread. #(listen-loop chan buf running? all-listeners))]
    (.start t)
    t))

(defn- sender-thread
  "Thread which runs the send-loop"
  [& args]
  (let [t (Thread. #(apply send-loop args))]
    (.start t)
    t))

(defn- chan-send
  "Standard :send-fn for a peer. Sends contents of send-buf out to the peer's
  :chan to the the address associated with the peer's ref :addr. :addr is typically
  added to a peer on creation. See client-peer and server-peer."
  [peer send-buf]
  (let [{:keys [chan addr]} peer]
    (.send chan send-buf @addr)))

(defn peer
  "Creat a generic peer which is capable of both sending and receiving messages
  on DatagramChannel :chan. Creates a thread for sending packets out using the
  fn in :send-fn (defaults to chan-send). Creates a thread for sending packets
  out by spawning a sending thread which will pull OSC message maps from the
  :send-q, encode them to binary and send them using the fn in :send-fn
  (defaults to chan-send).

  If passed an optional param listen? will also start a thread listening for
  incoming packets on chan. peers have listeners and handlers registered to
  recieve incoming messages.  A listener is sent every message received, and
  handlers are dispatched by OSC node (a.k.a. path)."
  [& [listen?]]
  (let [chan (DatagramChannel/open)
        rcv-buf (ByteBuffer/allocate BUFFER-SIZE)
        send-buf (ByteBuffer/allocate BUFFER-SIZE)
        send-q (PriorityBlockingQueue. OSC-SEND-Q-SIZE (comparator (fn [a b] (< (:timestamp (second a)) (:timestamp (second b))))))
        running? (ref true)
        handlers (ref {})
        default-listener (mk-default-listener handlers)
        listeners (ref {})
        send-thread (sender-thread running? send-q send-buf)
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
 "Returns an OSC client ready to communicate with a host on a given port."
 [host port]
 (when-not (integer? port)
   (throw (Exception. (str "port should be an integer - got: " port))))
 (when-not (string? host)
   (throw (Exception. (str "host should be a string - got:" host))))
  (let [peer (peer :with-listener)
        sock (.socket (:chan peer))
        local (.getLocalPort sock)]
    (.bind sock (InetSocketAddress. local))
    (assoc peer
           :host (ref host)
           :port (ref port)
           :addr (ref (InetSocketAddress. host port)))))

(defn update-peer-target
  "Update the target address of an OSC client so future calls to osc-send
  will go to a new destination. Also updates zeroconf registration."
  [peer host port]

  (when (:zero-conf-name peer)
    (unregister-zero-conf-service (:port peer)))

  (dosync
    (ref-set (:host peer) host)
    (ref-set (:port peer) port)
    (ref-set (:addr peer) (InetSocketAddress. host port)))

  (when (:zero-conf-name peer)
    (register-zero-conf-service (:zero-conf-name peer) port)))

(defn server-peer
  "Returns a live OSC server ready to register handler functions."
  [port zero-conf-name]
  (when-not (integer? port)
    (throw (Exception. (str "port should be an integer - got: " port))))
  (when-not (string? zero-conf-name)
    (throw (Exception. (str "zero-conf-name should be a string - got:" zero-conf-name))))
  (let [peer (peer :with-listener)
        sock (.socket (:chan peer))]

    (.bind sock (InetSocketAddress. port))
    (register-zero-conf-service zero-conf-name port)

    (let [peer (assoc peer
                 :host (ref nil)
                 :port (ref port)
                 :addr (ref nil)
                 :zero-conf-name zero-conf-name)]

      peer)))

(defn close-peer
  "Close a peer, also works for clients and servers."
  [peer & wait]
  (when (:zero-conf-name peer)
    (unregister-zero-conf-service (:port peer)))
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

(defn peer-send-bundle
  "Send OSC bundle to peer."
  [peer bundle]
  (when @osc-debug*
    (print-debug "osc-send-bundle: " bundle))
  (.put (:send-q peer) [peer bundle]))

(defn peer-send-msg
  "Send OSC msg to peer"
  [peer msg]
  (when @osc-debug*
    (print-debug "osc-send-msg: " msg))
  (.put (:send-q peer) [peer (assoc msg :timestamp 0)]))

(defn peer-handle
  "Register a new handler with peer on path with key."
  [peer path handler key]
  (when-not (string? path)
    (throw (IllegalArgumentException. (str "OSC handle path should be a string"))))
  (when (contains-illegal-chars? path)
    (throw (IllegalArgumentException. (str "OSC handle paths may not contain the following chars: " ILLEGAL-METHOD-CHARS))))
  (when (.endsWith path "/")
    (throw (IllegalArgumentException. (str "OSC handle needs a method name (i.e. must not end with /)"))))
  (when-not (.startsWith path "/")
    (throw (IllegalArgumentException. (str "OSC handle needs to start with /"))))
  (let [handlers (:handlers peer)
        path-parts (split-path path)

        phandlers (:handlers (get-in @handlers path-parts {:handlers {}}))]
    (dosync (alter handlers assoc-in (conj (vec path-parts) :handlers) (assoc phandlers key handler)))))

(defn peer-recv
  "Register a one-shot handler with peer with specified timeout. If timeout is
  nil then timeout is ignored."
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
  "Remove handlers from peer associated with path."
  [peer path]
  (let [handlers (:handlers peer)
        path-parts (split-path path)]
    (dosync
     (alter handlers assoc-in (conj (vec path-parts) :handlers) {}))))

(defn peer-rm-all-handlers
  "Remove all handlers from peer recursively down from path"
  ([peer path]
     (let [handlers (:handlers peer)
           path-parts (split-path path)]
       (dosync
        (if (empty? path-parts)
          (ref-set handlers {})
          (alter  handlers path-parts {}))))))

(defn peer-rm-handler
  "Remove handler from peer with specific key associated with path"
  [peer path key]
  (let [handlers (:handlers peer)]
    (remove-handler handlers path key)))
