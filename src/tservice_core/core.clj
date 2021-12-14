(ns tservice-core.core
  "Provide a robust plugin system for integrating new functions into the tservice."
  (:require [tservice-core.plugins.env :refer [setup-plugin-dir setup-workdir-root]]
            [tservice-core.plugins.core :refer [load-plugins!]]))

(def setup-custom-plugin-dir setup-plugin-dir)
(def setup-custom-workdir-root setup-workdir-root)

(defn start-plugins!
  "An entrypoint. You can load all plugins by using this function."
  []
  (load-plugins!))

(defn stop-plugins!
  []
  nil)
