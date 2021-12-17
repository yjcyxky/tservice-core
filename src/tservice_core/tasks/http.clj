(ns tservice-core.tasks.http
  (:require [tservice-core.plugins.plugin-proxy :refer [get-plugins-metadata get-plugin-context]]
            [clojure.string :as clj-str]
            [camel-snake-kebab.core :as csk]
            [clj-uuid :as uuid]
            [tservice-core.plugins.env :refer [get-workdir]]))

(defn- merge-plugins-metadata
  []
  (let [metadata (apply merge-with into (filter #(:routes %) (get-plugins-metadata)))
        routes (:routes metadata)
        manifests (:manifests metadata)]
    (concat []
            (filter #(or (:route %) (:manifest %)) (get-plugins-metadata))
            (map (fn [route] {:route route}) routes)
            (map (fn [manifest] {:manifest manifest}) manifests))))

(defn get-owner-from-headers
  "The first user is treated as the owner."
  [headers]
  (let [auth-users (get headers "x-auth-users")
        owner (when auth-users (first (clj-str/split auth-users #",")))]
    owner))

(defn- make-request-context
  {:added "0.6.0"}
  [plugin-name plugin-type owner]
  (let [uuid (str (uuid/v1))]
    {:owner owner
     :uuid uuid
     :workdir (if owner
                (get-workdir :username owner :uuid uuid)
                (get-workdir :uuid uuid))
     :plugin-context (merge (get-plugin-context plugin-name)
                            {:plugin-type (name plugin-type)})}))

(defn- make-handler
  [plugin-name plugin-type handler status-code]
  (fn [{{:keys [query path body]} :parameters
        {:as headers} :headers}]
    (let [owner (get-owner-from-headers headers)]
      {:status status-code
       :body (handler
              (merge {:path path :query query :body body :headers headers}
                     (make-request-context plugin-name plugin-type owner)))})))

(defmulti make-method
  {:added "0.7.0"}
  (fn [context] (:method-type context)))

(defmethod make-method :get
  make-get-method
  [{:keys [plugin-name plugin-type endpoint summary query-schema path-schema response-schema handler]}]
  (hash-map (keyword endpoint)
            {:get {:summary (or summary (format "A json schema for %s" plugin-name))
                   :parameters {:query query-schema
                                :path path-schema}
                   :responses {200 {:body (or response-schema map?)}}
                   :handler (make-handler plugin-name plugin-type handler 200)}}))

(defmethod make-method :post
  make-post-method
  [{:keys [plugin-name plugin-type endpoint summary
           body-schema query-schema path-schema
           response-schema
           handler]}]
  (hash-map (keyword endpoint)
            {:post {:summary (or summary (format "Create a(n) task for %s plugin %s." plugin-type plugin-name))
                    :parameters {:body body-schema
                                 :query query-schema
                                 :path path-schema}
                    :responses {201 {:body (or response-schema map?)}}
                    :handler (make-handler plugin-name plugin-type handler 201)}}))

(defmethod make-method :put
  make-put-method
  [{:keys [plugin-name plugin-type endpoint summary
           body-schema query-schema path-schema
           response-schema
           handler]}]
  (hash-map (keyword endpoint)
            {:put {:summary (or summary (format "Update a(n) task for %s plugin %s." plugin-type plugin-name))
                   :parameters {:body body-schema
                                :path path-schema
                                :query query-schema}
                   :responses {200 {:body (or response-schema map?)}}
                   :handler (make-handler plugin-name plugin-type handler 200)}}))

(defmethod make-method :delete
  make-delete-method
  [{:keys [plugin-name plugin-type endpoint summary
           path-schema body-schema query-schema
           response-schema
           handler]}]
  (hash-map (keyword endpoint)
            {:delete {:summary (or summary (format "Delete a(n) task for %s plugin %s." plugin-type plugin-name))
                      :parameters {:path path-schema :body body-schema :query query-schema}
                      :responses {200 {:body (or response-schema map?)}}
                      :handler (make-handler plugin-name plugin-type handler 200)}}))

(defn- ->methods
  "Convert the forms to the http methods.
   
   Form is a hash map which contains several elements for building http method. And different http method
   need different elements. such as get method need to have these keys: method-type, endpoint, summary,
   query-schema, path-schema, response-schema and handler."
  {:added "0.6.0"}
  [plugin-name plugin-type & forms]
  (map (fn [form] (make-method (merge {:plugin-name plugin-name
                                       :endpoint (or (:endpoint form) plugin-name)
                                       :plugin-type plugin-type} form))) forms))

(defn- merge-map-array
  "Merge a list of hash-map. 
   It will merge the value which have the same key into a vector, but the value must be a collection.
   
   Such as: [{:get {:a 1}} {:get {:b 2}}] -> {:get {:a 1 :b 2}}"
  {:added "0.6.0"}
  [array]
  (apply merge-with into array))

(defn- get-endpoint-prefix
  "Generate endpoint prefix from plugin-type.
   
   Such as: :DataPlugin --> /data"
  {:added "0.6.0"}
  [plugin-type]
  (str "/" (first (clj-str/split
                   (csk/->kebab-case (name plugin-type)) #"-"))))

(defn- get-tag
  "Generate swargger tag from plugin-type.
   
   Such as: :DataPlugin --> Data"
  {:added "0.6.0"}
  [plugin-type]
  (clj-str/capitalize
   (first (clj-str/split
           (csk/->kebab-case (name plugin-type)) #"-"))))

(defn make-routes
  "Make several forms into routes for plugin.
   
   Examples:
   (make-routes \"corrplot\" :ChartPlugin
                {:method-type :get
                 :endpoint \"report\"
                 :summary \"\"
                 :query-schema {}
                 :path-schema {}
                 :response-schema {}
                 :handler (fn [context] context)}
                {:method-type :post
                 :endpoint \"report\"
                 :summary \"\"
                 :body-schema {}
                 :response-schema {}
                 :handler (fn [context] context)}
                {:method-type :put
                 :endpoint \"report\"
                 :summary \"\"
                 :body-schema {}
                 :path-schema {}
                 :response-schema {}
                 :handler (fn [context] context)}
                {:method-type :get
                 :endpoint \"report\"
                 :summary \"\"
                 :path-schema {}
                 :response-schema {}
                 :handler (fn [context] context)})"
  {:added "0.6.0"}
  [plugin-name plugin-type & forms]
  (let [methods (apply ->methods plugin-name plugin-type forms)
        endpoints (merge-map-array methods)]
    {:routes (map (fn [[k v]] [(str (get-endpoint-prefix plugin-type)
                                    "/"
                                    (symbol k))  ;; (name :test/:sample_id) will return :sample_id, but we need test/:sample_id
                               (merge {:tags [(get-tag plugin-type)]}
                                      v)]) endpoints)}))

(defn get-routes
  "Return all routes which defined in plugins."
  []
  (->> (merge-plugins-metadata)
       (map #(:route %))
       (filter some?)))

(defn get-manifest
  "Return all manifests which defined in plugins."
  []
  (->> (merge-plugins-metadata)
       (map #(:manifest %))
       (filter some?)))
