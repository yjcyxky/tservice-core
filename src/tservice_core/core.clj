(ns tservice-core.core
  "Provide a robust plugin system for integrating new functions into the tservice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [local-fs.core :as fs]
            [tservice-core.plugins.env :refer [get-context-path plugins-dir setup-plugin-dir]]
            [tservice-core.plugins.classloader :as classloader]
            [tservice-core.plugins.initialize :as initialize]
            [tservice-core.plugins.plugin-proxy :refer [add-plugin-context]]
            [yaml.core :as yaml])
  (:import [java.nio.file Path]))

(def setup-custom-plugin-dir setup-plugin-dir)

(defn- extract-system-modules! []
  (println "Modules: " (io/resource "modules"))
  (when (io/resource "modules")
    (let [plugins-path (plugins-dir)]
      (fs/with-open-path-to-resource [modules-path "modules"]
        (fs/copy-files! modules-path plugins-path)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          loading/initializing plugins                                          |
;;; +----------------------------------------------------------------------------------------------------------------+
(defn- add-to-classpath! [^Path jar-path]
  (classloader/add-url-to-classpath! (-> jar-path .toUri .toURL)))

(defn- plugin-info [^Path jar-path]
  (some-> (fs/slurp-file-from-archive jar-path "tservice-plugin.yaml")
          yaml/parse-string))

(defn- init-plugin-with-info!
  "Initiaize plugin using parsed info from a plugin maifest. Returns truthy if plugin was successfully initialized;
  falsey otherwise."
  [info]
  (initialize/init-plugin-with-info! info)
  (when-let [plugin-name (:name (:plugin info))]
    (add-plugin-context plugin-name
                        {:plugin-name plugin-name
                         :plugin-version (:version (:info info))
                         :plugin-info (dissoc info :add-to-classpath! :jar-path)
                         :data-dir (get-context-path :data plugin-name)
                         :env-dir (get-context-path :env plugin-name)
                         :jar-path (.toString (:jar-path info))
                         :config-dir (get-context-path :config plugin-name)})))

(defn- init-plugin!
  "Init plugin JAR file; returns truthy if plugin initialization was successful."
  [^Path jar-path]
  (if-let [info (plugin-info jar-path)]
    ;; for plugins that include a tservice-plugin.yaml manifest run the normal init steps, don't add to classpath yet
    (init-plugin-with-info! (assoc info :add-to-classpath! #(add-to-classpath! jar-path) :jar-path jar-path))
    ;; for all other JARs just add to classpath and call it a day
    (add-to-classpath! jar-path)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 load-plugins!                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+
(defn- plugins-paths []
  (for [^Path path (fs/files-seq (plugins-dir))
        :let [file-name (.getFileName path)]
        :when      (and (fs/regular-file? path)
                        (fs/readable? path)
                        (or (str/ends-with? file-name "tservice-plugin.jar")
                            (str/ends-with? file-name "common-plugin.jar")))]
    path))

(defn- load-local-plugin-manifest! [^Path path]
  (some-> (slurp (str path)) yaml.core/parse-string initialize/init-plugin-with-info!))

(defn- load-local-plugin-manifests!
  "Load local plugin manifest files when running in dev or test mode, to simulate what would happen when loading those
  same plugins from the uberjar. This is needed because some plugin manifests define plugin methods and the like that
  aren't defined elsewhere."
  []
    ;; TODO - this should probably do an actual search in case we ever add any additional directories
  (log/info "Loading local plugins from " (fs/files-seq (fs/get-path "modules/plugins/")))
  (doseq [path  (fs/files-seq (fs/get-path "modules/plugins/"))
          :let  [manifest-path (fs/get-path (str path) "/resources/tservice-plugin.yaml")]
          :when (fs/exists? manifest-path)]
    (log/info (format "Loading local plugin manifest at %s" (str manifest-path)))
    (load-local-plugin-manifest! manifest-path)))

(defn- has-manifest? ^Boolean [^Path path]
  (boolean (fs/file-exists-in-archive? path "tservice-plugin.yaml")))

(defn- init-plugins! [paths]
  ;; sort paths so that ones that correspond to JARs with no plugin manifest (e.g. a dependency like the Oracle JDBC
  ;; driver `ojdbc8.jar`) always get initialized (i.e., added to the classpath) first; that way, TService plugins that
  ;; depend on them (such as Oracle) can be initialized the first time we see them.
  ;;
  ;; In Clojure world at least `false` < `true` so we can use `sort-by` to get non-Tservice-plugin JARs in front
  (doseq [^Path path (sort-by has-manifest? paths)]
    (try
      (init-plugin! path)
      (catch Throwable e
        (log/error e "Failed to initialize plugin " (.getFileName path))))))

(defn- load! []
  (log/info (format "Loading plugins in %s..." (str (plugins-dir))))
  (let [paths (plugins-paths)]
    (init-plugins! paths)))

(defonce ^:private load!* (delay (load!)))

(defn load-plugins!
  "Load TService plugins. The are JARs shipped as part of TService itself, under the `resources/modules` directory (the
  source for these JARs is under the `modules` directory); and others manually added by users to the TService plugins
  directory, which defaults to `./plugins`.
  When loading plugins, TService performs the following steps:
  *  TService creates the plugins directory if it does not already exist.
  *  Any plugins that are shipped as part of TService itself are extracted from the TService uberjar (or `resources`
     directory when running with `lein`) into the plugins directory.
  *  Each JAR in the plugins directory that *does not* include a TService plugin manifest is added to the classpath.
  *  For JARs that include a TService plugin manifest (a `tservice-plugin.yaml` file), a lazy-loading TService plugin
     is registered; when the plugin is initialized (automatically, when certain methods are called) the JAR is added
     to the classpath and the plugin namespace is loaded
  This function will only perform loading steps the first time it is called — it is safe to call this function more
  than once."
  []
  @load!*)

(defn start-plugins!
  "An entrypoint. You can load all plugins by using this function."
  []
  (when (some? (plugins-dir))
    (load-plugins!)))

(defn stop-plugins!
  []
  nil)
