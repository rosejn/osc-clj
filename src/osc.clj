(ns osc
  (:use [osc.util]
        [osc.peer]))

;; We use binding to *osc-msg-bundle* to bundle messages
;; and send combined with an OSC timestamp.
(def ^{:dynamic true} *osc-msg-bundle* nil)

(defn- osc-send-msg
  "Send OSC msg to peer."
  [peer msg]
  (if *osc-msg-bundle*
    (swap! *osc-msg-bundle* #(conj %1 msg))
    (peer-send-msg peer msg)))

(defn osc-listen
  "Attach a generic listener function that will be called with every incoming
  osc message. An optional key allows you to specifically refer to this listener
  at a later point in time. If no key is passed, the listener itself will also
  serve as the key.

  (osc-listen s (fn [msg] (println \"listener: \" msg)) :foo)."
  ([server listener] (osc-listen server listener listener))
  ([server listener key]
     (dosync
      (alter (:listeners server) assoc key listener))
     server))

(defn osc-listeners
  "Return a seq of the keys of all registered listeners. This may be the
  listener fns themselves if no key was explicitly specified when the listener
  was registered."
  [server]
  (keys @(:listeners server)))

(defn osc-rm-listener
  "Remove the generic listener associated with the specific key
  (osc-rm-listener s :foo)"
  [server key]
  (dosync
   (alter (:listeners server) dissoc key))
  server)

(defn osc-rm-all-listeners
  "Remove all generic listeners associated with server"
  [server]
  (dosync
   (ref-set (:listeners server) {}))
  server)

(defn osc-handle
  "Add a handle fn (a method in OSC parlance) to the specified OSC path
  (container). This handle will be called when an incoming OSC message matches
  the supplied path. This may either be a direct match, or a pattern match if
  the incoming OSC message uses wild card chars in its path.  The path you
  specify may not contain any of the OSC reserved chars:
  # * , ? [ ] { } and whitespace

  Will override and remove any handler already associated with the supplied
  path. If the handler-fn returns :done it will automatically remove itself."
  [server path handler]
  (peer-handle server path handler)
  server)

(defn osc-handlers
  "Returns a seq of all the paths containing a handler for the server. If a
  path is specified, the result will be scoped within that subtree."
  ([server] (osc-handlers server "/"))
  ([server path]
     (peer-handler-paths server path)))

(defn osc-rm-handler
  "Remove the handler at the specified path.
  specific handler (if found)"
  [server path]
  (peer-rm-handler server path)
  server)

(defn osc-rm-all-handlers
  "Remove all registered handlers for the supplied path (defaulting to /)
  This not only removes the handler associated with the specified path
  but also all handlers further down in the path tree. i.e. if handlers
  have been registered for both /foo/bar and /foo/bar/baz and
  osc-rm-all-handlers is called with /foo/bar, then the handlers associated
  with both /foo/bar and /foo/bar/baz will be removed."
  ([server] (osc-rm-all-handlers server "/"))
  ([server path]
     (peer-rm-all-handlers server path)
     server))

(defn osc-recv
  "Register a one-shot handler which will remove itself once called. If a
  timeout is specified, it will return nil if a message matching the path
  is not received within timeout milliseconds. Otherwise, it will block
  the current thread until a message has been received.

  Will override and remove any handler already associated with the supplied
  path."
  [server path handler & [timeout]]
  (peer-recv server path handler timeout)
  server)

(defn osc-send
  "Creates an OSC message and either sends it to the server immediately
  or if a bundle is currently being formed it adds it to the list of messages."
  [client path & args]
  (osc-send-msg client (apply mk-osc-msg path (osc-type-tag args) args)))

(defn osc-msg
  "Returns a map representing an OSC message with the specified path and args."
  [path & args]
  (apply mk-osc-msg path (osc-type-tag args) args))

(defn osc-bundle
  "Returns an OSC bundle, which is a timestamped set of OSC messages and/or bundles."
  [timestamp & items]
  (mk-osc-bundle timestamp items))

(defn osc-send-bundle
  "Send OSC bundle to client."
  [client bundle]
  (peer-send-bundle client bundle))

(defmacro in-osc-bundle
  "Send a bundle with associated timestamp enclosing the messages in body. Can
  be used to wrap around an arbtrary form. All osc-sends within will be
  automatically added to the bundle."
  [client timestamp & body]
  `(binding [*osc-msg-bundle* (atom [])]
     (let [res# (do ~@body)]
       (osc-send-bundle ~client (mk-osc-bundle ~timestamp @*osc-msg-bundle*))
       res#)))

(defn osc-client
 "Returns an OSC client ready to communicate with a host on a given port.
 Use :protocol in the options map to \"tcp\" if you don't want \"udp\"."
  [host port]
  (client-peer host port))

(defn osc-peer
  "Returns a generic OSC peer. You will need to configure it to make
  it act either as a server or client."
  []
  (peer))

(defn osc-target
  "Update the target address of an OSC client so future calls to osc-send
  will go to a new destination. Automatically updates zeroconf if necessary."
  [client host port]
  (update-peer-target client host port)
  client)

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
  (apply close-peer peer wait)
  peer)

(defn osc-debug
  [& [on-off]]
  (let [on-off (if (= on-off false) false true)]
    (dosync (ref-set osc-debug* on-off))))

(defn zero-conf-on
  "Turn zeroconf on. Will automatically register all running servers with their
  specified service names (defaulting to \"osc-clj\" if none was specified).
  Asynchronous."
  []
  (turn-zero-conf-on))

(defn zero-conf-off
  "Turn zeroconf off. Will unregister all registered services and close zeroconf
  down. Asynchronous."
  []
  (turn-zero-conf-off))

(defn zero-conf?
  "Returns true if zeroconf is running, false otherwise."
  []
  (zero-conf-running?))

(defn osc-now
  "Return the current time in milliseconds"
  []
  (System/currentTimeMillis))
