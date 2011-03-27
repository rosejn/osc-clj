  osc-clj
==============

#### Open Sound Control library for Clojure

An OSC communication library created for Project Overtone.  It implements OSC
1.0 with some additional types from 1.1 supported also.  Find OSC documentation
here:

http://opensoundcontrol.org/spec-1_0

The library implements both a client and a server.  Check out the tests for
additional examples and read the source.

    (use 'osc)
    
    (def PORT 4242)
    
    ; start a server and create a client to talk with it
    (def server (osc-server PORT)) 
    (def client (osc-client "localhost" PORT))
    
    ; Register a handler function for the /test OSC address
    ; The handler takes a message map with the following keys:
    ;   {:src-host, :src-port, :path, :type-tag, :args}
    (osc-handle server "/test" (fn [msg] (println "MSG: " (:args msg))))
    
    ; send it some messages
    (doseq [val (range 10)]
     (osc-send client "/test" "i" val))
    
    (Thread/sleep 1000)
    
    ; stop listening and deallocate resources
    (osc-close client)
    (osc-close server)

There is support for sending timestamped OSC bundles, (receiving them hasn't
been implemented yet),  which is how you can get sample accurate messaging for
use with SuperCollider and other OSC servers.  All OSC messages and bundles sent
within the scope of a bundle will get sent together in the parent bundle with
the specified timestamp.

    ; Send the enclosing messages inside a bundle that is timestamped for
    ; 1 second from now.
    (in-osc-bundle client (+ (osc-now) 1000)
      (osc-send client "/foo" "i" 42)
      
      ; Call functions that send more osc messages
      (do-stuff client))


### Project Info:

Include in your project.clj like so:

  [overtone/osc-clj "0.1.2-SNAPSHOT"]

#### Source Repository
Downloads and the source repository can be found on GitHub:

  http://github.com/rosejn/osc-clj

Eventually there will be more documentation for this library, but in the
meantime you can see it in use within the context of Project Overtone, located
here:

  http://github.com/rosejn/overtone


#### Mailing List

For any questions, comments or patches, use the Overtone google group here:

http://groups.google.com/group/overtone

### Author

* Jeff Rose

### Contributors
* mw10013
