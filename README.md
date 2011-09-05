                                   ,~~~
                                   +~~~~~
                                   ?~~~~+~
                                   8~~~~~+~,
                                   O~~~~~=+==
                                   8?~~~~=+=~~
                                   NI=~~~~~~~~~
                                   DO?~~=7I~~~~=
                                   Z8?~~~~~~:+~?$I,           ,,,,,
                                   ZNOI~~~~~~~~~~~.~7$$Z7:~~ZZZOSCZZ+,
                                   ZDO7:~~~=~~~~~~~         $MZCSOZZ,~
                                   ZDOOI~~?~~~~=====        ~DZO7
                                   ~NDOO?~~~~~~~~~~~        ~7:$
                                   :I OO$+~~~~==~~==~      ~? Z
                                   DDDD8D?~~~~~~~====~    ~: I$
                                  D DDDN8Z=~~~~~===~~~~  +   I`
                                 ? DDDDN8O7~~~~~~~~====.7   $$
                                  N888DNMOO?~~~~~===~~~$?  .?
                              O88+DM ~NNN88OI~~~~~=~~~+~~?/$
                               D  OVERTONE88I~~~~~===~~~,~
                               D  ~ INC.NNN888I~~~~~=~~==,
                               :I   ND~ DDN888O~~~?======,
                                8  8DDD ~ M88887:I~~=====
                                D 8DDDDND   D 88$~~~===+z
                                :D8MDDDNN     DD87~~I~+=
                                 88DDDDDN       DD7?~~
                                 $CLOJURE         DD`
                                 $8DSDDDD
                                 DDDCDDDN                  888  d8b
                              ::7OLIBRARY$$$77I$IZ7        888  Y8P
                                                           888
                  .d88b.  .d8888b   .d8888b        .d8888b 888 8888
                 d88""88b 88K      d88P"          d88P"    888 "888
                 888  888 "Y8888b. 888     888888 888      888  888
                 Y88..88P      X88 Y88b.          Y88b.    888  888
                  "y88p"   88888P'  "Y8888P        "Y8888P 888  888
                                                                888
                                                               d88P
                                                             888P"



# Open Sound Control Library for Clojure

A full-featured UDP-based OSC communication library created for Project Overtone.  It implements OSC 1.0 with some additional types from 1.1 also supported (long and double).  Find OSC documentation here:

http://opensoundcontrol.org/spec-1_0


## Quick Start

    (use 'overtone.osc)

    (def PORT 4242)

    ; start a server and create a client to talk with it
    (def server (osc-server PORT))
    (def client (osc-client "localhost" PORT))

    ; Register a handler function for the /test OSC address
    ; The handler takes a message map with the following keys:
    ;   [:src-host, :src-port, :path, :type-tag, :args]
    (osc-handle server "/test" (fn [msg] (println "MSG: " msg)))

    ; send it some messages
    (doseq [val (range 10)]
     (osc-send client "/test" "i" val))

    (Thread/sleep 1000)

    ;remove handler
    (osc-rm-handler server "/test")

    ; stop listening and deallocate resources
    (osc-close client)
    (osc-close server)

## Documentation

### OSC Client

The `osc-clj` client allows you to send OSC messages to a server listening on a specific port on a specific host.

#### OSC Messages

OSC messages contain the following elements:

* A path (i.e. `/foo/bar/baz`)
* An arbitrary list of params (i.e. `2 "baz" 3.14159265 (byte-array 10)`

The supported param types are:

* Integers
* Floats
* Strings
* Byte Arrays
* Longs
* Doubles

You don't need to specify which type you are using, `osc-clj` will infer these with reflection.

Each message may or may not trigger a method handler to be executed on the receiving server - this is dependent on whether the path sent matches a registered handler on the server. Multiple handlers can be called by using regexp-like patterns in your out going path which may be matched to multiple handlers (see the pattern matching section below for more info).

#### Sending Messages

In order to send messages, you must first create a client withi `osc-client`:

    (def client (osc-client "localhost" 9801))

Then you may send a message using `osc-send`:

    (osc-send client "/foo/bar/baz 1 2 "three")


### OSC Server

The `osc-clj` server allows you to receive OSC messages by listening on a specific port. You may then register method handlers and/or listeners with the server which may be triggered by incoming messages.

Create new server with `osc-server`:

    (def server (osc-server 9800))

#### OSC Listeners

`osc-clj` servers support both listeners and handlers. Listeners are fns you register which will be triggered for each and every incoming message. The fn must accept one argument - the message to receive. Each listener may also be associated with a unique key which allows you to individually remove it at a later time.

Here we use `osc-listen` to add a generic listener that will print *all* incoming OSC messages to std-out. We also specify the key `:debug`:

    (osc-listen server (fn [msg] (println "Listener: " msg)) :debug)

To remove the listener simply call:

    (osc-rm-listener server :debug)

`osc-clj` also supplies the fn `osc-rm-all-listeners` which will remove all listeners on the specified server.

#### OSC Method Handlers

Handlers are registered with an OSC path such as /foo/bar - they are only triggered if the path of the incoming OSC message matches the registered path. Only one handler may be registered with any given path.

To register a handler for the path "/foo/bar" do the following:

    (osc-handle s "/foo/bar" (fn [msg] (println "Handler for /foo/bar: " msg)))

This will only print out received messages that match the path "/foo/bar". To remove call:

    (osc-rm-handler s "/foo/bar")

`osc-clj` also supplies the fn `osc-rm-all-handlers` which will remove all the servers handlers within the path and below in the hierarchy (i.e. `(osc-rm-all-handlers server "/foo")` will remove all handlers registered at /foo and any below i.e. /foo/bar /foo/bar/baz etc. Passing no path to `osc-rm-all-handlers` will remove *all* handlers on the server.

### OSC Bundles

OSC bundles are groups of messages with a collective timestamp. This allows groups of messages to be scheduled to be handled at the same time which may be some arbitrary point in the future.

#### Receiving Bundles

When `osc-clj` receives a timestamped bundle it will schedule the bundle to be handled at the time of the timestamp. However, if the time is in the past, it will be handled immediately.

Handling the bundle means unpacking it and handling each contained message in sequence. Therefore, if a bundle contains another, and the inner bundle's timestamp is earlier than the outer bundle, it will *not* be honoured - instead it will trigger at the outer bundle's timestamp.

#### Sending Bundles

The simplest way to construct and send a bundle is with the macro `in-osc-bundle`. All OSC messages and bundles sent within the scope of the macro call will get sent together in the parent bundle with the specified timestamp:

Send the enclosing messages inside a bundle that is timestamped for 1 second from now:

    (in-osc-bundle client (+ (osc-now) 1000)
      (osc-send client "/foo" "bar" 42)

      ; Call functions that send more osc messages
      (do-stuff client))

You can also create bundles by hand with `osc-bundle` and send them with `osc-send-bundle`. OSC messages can also be created with `osc-msg` in order to populate your bundle.

When constructing bundles, if you specify the timestamp with a Long, you can sample accurate messaging (with precision granularity around 200 picoseconds) for use with SuperCollider and other OSC servers. Bundles received with future timestamps are scheduled to be executed at that future time (with a precision granularity of around 10 milliseconds).


### Pattern Matching

`osc-clj` has full support for OSC pattern matching. This allows incoming messages to specify regexp like matcher symbols such as `?` and `*` allowing the message to match more than one path.

The basic matchers are:

* `*` Matches 0 or more arbitrary chars (`osc-clj` implements this in a non-greedy way)
* `?` Matches 1 arbitrary char
* `[abc]` Matches 1 char: either a, b or c
* `[!abc]` Matches 1 char: not a,b or c
* `[a-d]` Matches 1 char: in the range a-d inclusive `[!a-d]` is also recognised.
* `{foo,bar}` Matches 1 word - either foo or bar


The matchers may also be combined:

    /foo/{bar,baz}/*/*quux?/[abc]def[!h]/phasor[0-9]/

There is no guarantee on the order of fn triggering.

For example, `/foo/{bar,baz}/quux` will trigger fns in both `/foo/bar/quux/` and `/foo/baz/quux` but with no specific order.

### Zeroconf

`osc-clj` has support for broadcasting the presence of your OSC servers to the local network using zerconf. This is disabled by default.

On creation of a server, you may specify an option tag:

    (def server (osc-server 9800 "My OSC Server"))

The string `My OSC Server` is then used to register your server with zeroconf. In order to use zeroconf you must turn it on:

    (zero-conf-on)

You should now see your server with clients that speak zeroconf. It is known that zero-conf can eat up a lot of cpu time - especially on chatty networks. It is therefore recommended to switch it off once you have configured and connected your client:

    (zero-conf-off)

## Project Info:

Include in your `project.clj like so:

    [overtone/osc-clj "0.5.0"]

### Source Repository
Downloads and the source repository can be found on GitHub:

  http://github.com/overtone/osc-clj


### Example Usage

For an example of this library in use within the check out Overtone, located here:

  http://github.com/overtone/overtone


### Mailing List

For any questions, comments or patches, use the Overtone google group:

http://groups.google.com/group/overtone

## Authors

* Jeff Rose
* Sam Aaron

### Contributors
* mw10013
