(ns tservice-core.tasks.async
  "Provides a very simply event bus using `core.async` to allow publishing and subscribing to interesting topics
   happening throughout the tservice system in a decoupled way. It's based on `metabase.events`.

   ## Regarding Events Initialization:

   Internal Events: The most appropriate way to initialize event listeners in any `tservice.events.*` namespace 
   (you can use [[setup-custom-namespace]] to change it) is to implement the
   `events-init` function which accepts zero arguments. This function is dynamically resolved and called exactly once
   when the application goes through normal startup procedures. Inside this function you can do any work needed and add
   your events subscribers to the bus as usual via `start-event-listener!`.

   Examples:

   ```clojure
   ; Call these code in app entrypoint.
   ;; First Step
   (setup-custom-namespace \"tservice\" :sub-ns \"events\")

   ;; Second Step (Call it when the app instance is launched.)
   (initialize-events!)

   ; Defined in each event.
   ;; Define event handler
   (defn- test-handler! 
      [{:keys [test-content]}]
      (println test-content))
   
   ;; Define events-init function.
   (def events-init
     \"Automatically called during startup; start event listener for test event.\"
     (make-events-init \"test\" test-handler!))
   
   ; When you need to trigger an event, call this code 
   (publish-event! \"test\" {:test-content \"This is a test.\"})
   ```

   External Events: If you use the event bus in plugin, you need to use [[make-events-init]] to 
   generate event initializer and initialize the event in plugin configuration file.
   When the above setting is successful, you can use publish-event! to trigger an event.
  "
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.java.classpath :as classpath]
            [tservice-core.plugins.classloader :as classloader]
            [tservice-core.plugins.util :as u]))

;;; -------------------------------------------------- PUBLICATION ---------------------------------------------------
(def ^:private events-channel
  "Channel to host events publications."
  (async/chan))

(def ^:private events-publication
  "Publication for general events channel. Expects a map as input and the map must have a `:topic` key."
  (async/pub events-channel :topic))

;;; -------------------------------------------------- SUBSCRIPTION --------------------------------------------------
(defn- subscribe-to-topic!
  "Subscribe to a given topic of the general events stream. Expects a topic to subscribe to and a `core.async` channel.
  Returns the channel to allow for chaining."
  [topic channel]
  {:pre [(keyword topic)]}
  (async/sub events-publication (keyword topic) channel)
  channel)

(defn- subscribe-to-topics!
  "Convenience method for subscribing to a series of topics against a single channel."
  [topics channel]
  {:pre [(coll? topics)]}
  (doseq [topic topics]
    (subscribe-to-topic! topic channel)))

(defn- start-event-listener!
  "Initialize an event listener which runs on a background thread via `go-loop`."
  [topics channel handler-fn]
  {:pre [(seq topics) (fn? handler-fn)]}
  ;; create the core.async subscription for each of our topics
  (subscribe-to-topics! topics channel)
  ;; start listening for events we care about and do something with them
  (async/go-loop []
    ;; try/catch here to get possible exceptions thrown by core.async trying to read from the channel
    (try
      (handler-fn (async/<! channel))
      (catch Throwable e
        (log/error e "Unexpected error listening on events")))
    (recur)))

;;; --------------------------------------------------- Internal Event Bus -------------------------------------------------
(def ^:private custom-namespace-prefix (atom "tservice"))
(def ^:private custom-namespace (atom "tservice.events.*"))

(defn- namespace-symbs* []
  (for [ns-symb (distinct
                 (ns-find/find-namespaces (concat (classpath/system-classpath)
                                                  (classpath/classpath (classloader/the-classloader)))))
        :when   (and (.startsWith (name ns-symb) @custom-namespace-prefix)
                     (not (.contains (name ns-symb) "test")))]
    ns-symb))

(def ^:private namespace-symbols
  "Delay to a vector of symbols of all tservice namespaces, excluding test namespaces.
    This is intended for use by various routines that load related namespaces, such as task and events initialization.
    Using `ns-find/find-namespaces` is fairly slow, and can take as much as half a second to iterate over the thousand
    or so namespaces that are part of the tservice project; use this instead for a massive performance increase."
  ;; We want to give JARs in the ./plugins directory a chance to load. At one point we have this as a future so it
  ;; start looking for things in the background while other stuff is happening but that meant plugins couldn't
  ;; introduce new tservice namespaces such as plugins.
  (delay (vec (namespace-symbs*))))

(defonce ^:private events-initialized?
  (atom nil))

(defn- find-and-load-event-handlers!
  "Search Classpath for namespaces that start with `tservice.events.`, and call their `events-init` function if it exists."
  []
  (doseq [ns-symb @namespace-symbols
          :when   (.startsWith (name ns-symb) @custom-namespace)]
    (classloader/require ns-symb)
      ;; look for `events-init` function in the namespace and call it if it exists
    (when-let [init-fn (ns-resolve ns-symb 'events-init)]
      (log/info "Starting events listener:" (u/format-color 'blue ns-symb) "👂")
      (init-fn))))

(defn setup-custom-namespace
  [namespace & {:keys [sub-ns]
                :or {sub-ns "events"}}]
  (reset! custom-namespace-prefix namespace)
  (reset! custom-namespace (str/join "." [namespace sub-ns "*"])))

(defn initialize-events!
  "Initialize the asynchronous internal events system.
   You need to call it when the app is launched."
  []
  (when-not @events-initialized?
    (find-and-load-event-handlers!)  ; Internal event handlers
    (reset! events-initialized? true)))

(defn stop-events!
  "Stop our events system."
  []
  (when @events-initialized?
    (log/info "Stoping events listener...")
    (reset! events-initialized? nil)))

;;; ------------------------------------------------ HELPER FUNCTIONS ------------------------------------------------
(defn topic->model
  "Determine a valid `model` identifier for the given TOPIC."
  [topic]
  ;; Just take the first part of the topic name after splitting on dashes.
  (name topic))

(defn object->model-id
  "Determine the appropriate `model_id` (if possible) for a given OBJECT."
  [topic object]
  (if (contains? (set (keys object)) :id)
    (:id object)
    (let [model (topic->model topic)]
      (get object (keyword (format "%s_id" model))))))

(defn object->user-id
  "Determine the appropriate `user_id` (if possible) for a given OBJECT."
  [object]
  (or (:actor_id object) (:user_id object) (:creator_id object)))

; TODO: There may be a conflict when the topics were added from different plugins.
(defonce ^:private plugin-events
  (atom {}))

(defn- register-plugin-event!
  "Register an event into the plugin-events."
  [event-name event-handler]
  (reset! plugin-events
          (merge @plugin-events
                 (hash-map (keyword event-name) event-handler))))

(defn- make-event-process
  "Make a event process which handle processing for a single event notification received on the plugin-channel"
  []
  (fn [plugin-event]
    (log/debug "Make Event Process: " plugin-event @plugin-events)
    ;; try/catch here to prevent individual topic processing exceptions from bubbling up.  better to handle them here.
    (try
      (when-let [{topic :topic object :item} plugin-event]
        ;; TODO: only if the definition changed??
        (if-let [event-handler (topic @plugin-events)]
          (event-handler object)
          (log/warn (format "No such event %s. (Events: %s)" @plugin-events plugin-event))))
      (catch Throwable e
        (log/warn (format "Failed to process %s event. %s" (:topic plugin-event) e))))))

(defn- get-plugin-topics
  "The `Set` of event topics which are subscribed to for use in tracking."
  []
  (keys @plugin-events))

(defn publish-event!
  "Publish an item into the events stream. Returns the published item to allow for chaining."
  {:style/indent 1}
  [topic event-item]
  {:pre [(keyword topic)]}
  (async/go (async/>! events-channel {:topic (keyword topic), :item event-item}))
  event-item)

(defn make-events-init
  "Generate event initializer."
  [event-name event-handler]
  ;; Must register event before generating event initializer.
  (register-plugin-event! event-name event-handler)
  (fn []
    ; Channel for receiving event we want to subscribe to for plugin events.
    (let [channel (async/chan)]
      (start-event-listener! (get-plugin-topics) channel (make-event-process)))))
