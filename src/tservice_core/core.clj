(ns tservice-core.core
  "Provide a robust plugin system for integrating new functions into the tservice."
  (:require [tservice-core.plugins.env :refer [setup-plugin-dir setup-workdir-root setup-config]]
            [tservice-core.plugins.core :refer [load-plugins!]]
            [tservice-core.tasks.async :as async-task]))

;; External Plugin System
(def setup-custom-plugin-dir setup-plugin-dir)
(def setup-custom-workdir-root setup-workdir-root)

(def setup-plugin-configs setup-config)

(defn start-plugins!
  "An entrypoint. You can load all plugins by using this function."
  []
  (load-plugins!))

(defn stop-plugins!
  []
  nil)

;; Internal Event Bus
(def setup-custom-namespace async-task/setup-custom-namespace)

(defn start-events!
  []
  (async-task/initialize-events!))

(defn stop-events!
  []
  (async-task/stop-events!))
