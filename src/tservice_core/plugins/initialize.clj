(ns tservice-core.plugins.initialize
  "Logic related to initializing plugins, i.e. running the `init` steps listed in the plugin manifest. This is done when
   TService launches as soon as all dependencies for that plugin are met; for plugins with unmet dependencies, it is
   retried after other plugins are loaded (e.g. for things like BigQuery which depend on the shared Google driver.)
   Note that this is not the same thing as initializing *plugins* -- plugins are initialized lazily when first needed;
   this step on the other hand runs at launch time and sets up that lazy load logic.
  "
  (:require [clojure.tools.logging :as log]
            [tservice-core.plugins.dependencies :as deps]
            [tservice-core.plugins.init-steps :as init-steps]
            [tservice-core.plugins.util :as u]
            [tservice-core.plugins.env :refer [get-context-path]]
            [local-fs.core :as fs]
            [schema.core :as s]))

(defonce ^:private initialized-plugin-names (atom #{}))

(defn- init!
  [{:keys [add-to-classpath! jar-path], init-steps :init
    {plugin-name :name} :info, plugin-or-plugins :plugin, :as manifest}]
  {:pre [(string? plugin-name)]}

  (when (deps/all-dependencies-satisfied? @initialized-plugin-names manifest)
    (let [plugins (u/one-or-many plugin-or-plugins)
          plugin-name (:name (:plugin manifest))
          env-dest-dir (fs/join-paths (fs/parent-path jar-path) "envs")
          config-dir (get-context-path :config plugin-name)
          data-dir (get-context-path :data plugin-name)
          context {:jar-path jar-path
                   :plugin-name plugin-name
                   :env-dest-dir env-dest-dir
                   :config-dir config-dir
                   :data-dir data-dir
                   :plugin-info (:info manifest)}]
      ;; if *any* of the plugins is not lazy-load, initialize it now
      (when (some false? (map :lazy-load plugins))
        (when add-to-classpath!
          (add-to-classpath!))
        (doseq [dir [env-dest-dir config-dir data-dir]]
          (when (not (fs/exists? dir))
            (fs/create-directories! dir)))
        (init-steps/do-init-steps! init-steps context)))
    ;; record this plugin as initialized and find any plugins ready to be initialized because depended on this one !
    ;;
    ;; Fun fact: we already have the `plugin-initialization-lock` if we're here so we don't need to worry about
    ;; getting it again
    (let [plugins-ready-to-init (deps/update-unsatisfied-deps! (swap! initialized-plugin-names conj plugin-name))]
      (when (seq plugins-ready-to-init)
        (log/debug (u/format-color 'yellow (format "Dependencies satisfied; these plugins will now be loaded: %s"
                                                   (mapv (comp :name :info) plugins-ready-to-init)))))
      (doseq [plugin-info plugins-ready-to-init]
        (init! plugin-info)))
    :ok))

(defn- initialized? [{{plugin-name :name} :info}]
  (@initialized-plugin-names plugin-name))

(s/defn init-plugin-with-info!
  "Initiaize plugin using parsed info from a plugin maifest. Returns truthy if plugin was successfully initialized;
   falsey otherwise."
  [info :- {:info     {:name s/Str, :version s/Str, s/Keyword s/Any}
            s/Keyword s/Any}]
  (or
   (initialized? info)
   (locking initialized-plugin-names
     (or
      (initialized? info)
      (init! info)))))
