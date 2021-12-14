(ns tservice-core.tasks.http
  (:require [tservice-core.plugins.plugin-proxy :refer [get-plugins-metadata]]))

(defn- merge-plugins-metadata
  []
  (let [metadata (apply merge-with into (filter #(:routes %) (get-plugins-metadata)))
        routes (:routes metadata)
        manifests (:manifests metadata)]
    (concat []
            (filter #(or (:route %) (:manifest %)) (get-plugins-metadata))
            (map (fn [route] {:route route}) routes)
            (map (fn [manifest] {:manifest manifest}) manifests))))

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
