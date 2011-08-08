(ns osc
  (:use [osc.util]
        [osc.peer]))

(defn osc-listen
  "Attach a generic listener function that will be called with every incoming osc message.
  (osc-listen s (fn [msg] (println \"listener: \" msg)) :foo)"
  [peer listener key]
  (dosync
   (alter (:listeners peer) assoc key listener)))

(defn osc-rm-listener
  "Remove the generic listener associated with the specific key
  (osc-rm-listener s :foo)"
  [peer key]
  (dosync
   (alter (:listeners peer) dissoc key)))

(defn osc-listeners
  "Return a seq of all registered listeners
  (osc-listeners s) ;=> (:foo)"
  [peer]
  (keys @(:listeners peer)))

(defn osc-rm-all-listeners
  "Remove all generic listeners associated with peer"
  [peer]
  (dosync
   (ref-set (:listeners peer) {})))

(defn osc-handle
  "Add a handle fn to an OSC path with the specified key. This handle will be
  called when an incoming OSC message matches the supplied path. This may either
  be a direct match, or a pattern match if the incoming OSC message uses wild
  card chars in its path (Pattern matching not implemented yet).

  The path you specify may not contain any of the OSC reserved chars:
  # * , ? [ ] { } and whitespace"
  ([peer path handler] (osc-handle peer path handler handler))
  ([peer path handler key]
     (peer-handle peer path handler key)))

(defn osc-rm-handler
  "Remove the handler at path with the specified key. This just removes one
  specific handler (if found)"
  [peer path key]
  (peer-rm-handler peer path key))

(defn osc-rm-handlers
  "Remove all the handlers at path."
  [peer path]
  (peer-rm-handlers peer path))

(defn osc-rm-all-handlers
  "Remove all registered handlers for the supplied path (defaulting to /)
  This not only removes the handlers associated with the specified path
  but also all handlers further down in the path tree. i.e. if handlers
  have been registered for both /foo/bar and /foo/bar/baz and
  osc-rm-all-handlers is called with /foo/bar, then all handlers associated
  with both /foo/bar and /foo/bar/baz will be removed."
  ([peer] (osc-rm-all-handlers peer "/"))
  ([peer path] (peer-rm-all-handlers peer path)))

(defn osc-recv
  "Register a one-shot handler which will remove itself once called. If a
  timeout is specified, it will return nil if a message matching the path
  is not received within timeout milliseconds. Otherwise, it will block
  the current thread until a message has been received."
  [peer path & [timeout]]
  (peer-recv peer path timeout))

(defn osc-send
  "Creates an OSC message and either sends it to the server immediately
  or if a bundle is currently being formed it adds it to the list of messages."
  [client path & args]
  (osc-send-msg client (apply osc-msg path (osc-type-tag args) args)))

(defn osc-client
 "Returns an OSC client ready to communicate with a host on a given port.
 Use :protocol in the options map to \"tcp\" if you don't want \"udp\"."
  [host port]
  (client-peer host port))

(defn osc-target
  "Update the target address of an OSC client so future calls to osc-send
  will go to a new destination. Automatically updates zeroconf if necessary."
  [peer host port]
  (update-peer-target peer host port))

(defn osc-server
  "Returns a live OSC server ready to register handler functions. By default
  this also registers the server with zeroconf. The name used to register
  can be passed as an optional param. If the zero-conf-name is set to nil
  zeroconf wont' be used."
  ([port] (osc-server port "osc-clj"))
  ([port zero-conf-name] (server-peer port zero-conf-name)))

(defn osc-close
  "Close an osc-peer, works for both clients and servers. If peer has been
  registered with zeroconf, it will automatically remove it."
  [peer & wait]
  (apply close-peer peer wait))

(defn osc-debug
  [& [on-off]]
  (let [on-off (if (= on-off false) false true)]
    (dosync (ref-set osc-debug* on-off))))

(defn osc-remove-zero-conf
  "Unregister any zeroconf services registered in this session"
  []
  (peer-unregister-all-zero-conf-services))
