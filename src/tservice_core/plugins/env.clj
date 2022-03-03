(ns tservice-core.plugins.env
  (:require [local-fs.core :as fs]
            [clojure.tools.logging :as log]
            [tservice-core.plugins.util :as u])
  (:import [java.nio.file Files Path]))

(defonce ^:private fn-create-task (atom nil))
(defonce ^:private fn-update-task (atom nil))
(defonce ^:private fn-make-remote-link (atom nil))
(defonce ^:private workdir-root (atom nil))
(defonce ^:private custom-plugin-dir (atom nil))
(defonce ^:private config (atom {}))

(defn get-workdir-root
  []
  @workdir-root)

(defn setup-workdir-root
  "Set a root directory for the working directory of a plugin."
  [^String dir]
  (reset! workdir-root dir))

(defn get-plugin-dir
  []
  @custom-plugin-dir)

(defn setup-plugin-dir
  "Set a custom plugin directory."
  [^String dir]
  (reset! custom-plugin-dir dir))

(defn get-plugin-config
  [plugin-name]
  ((keyword plugin-name) @config))

(defn setup-config
  "Pass the instance's config to plugin system."
  [c]
  (reset! config c))

(defn setup-fns
  [create-task-fn update-task-fn make-remote-link-fn]
  (reset! fn-create-task create-task-fn)
  (reset! fn-update-task update-task-fn)
  (reset! fn-make-remote-link make-remote-link-fn))

(defn create-task!
  {:added "0.2.0"}
  [payload]
  (if @fn-create-task
    (@fn-create-task payload)
    (log/warn "No function for creating task.")))

(defn update-task!
  {:added "0.2.0"}
  [payload]
  (if @fn-update-task
    (@fn-update-task payload)
    (log/warn "No function for updating task.")))

(defn make-remote-link
  "Make a remote link for a local file when you need to make the local file be accessed by network,
   such as minio/s3/oss link. 
   
   CAUTION: The path will be returned directly When a make-remote-link function wasn't find.
   
   - Why you need to set fn-make-remote-link by setup-fns?
   We don't know how to create a remote link in the specified environment but you know."
  {:added "0.2.1"}
  [abspath]
  (if @fn-make-remote-link
    (@fn-make-remote-link abspath)
    (do
      (log/warn "No function for making a remote link.")
      abspath)))

(defonce ^:private context-dirs (atom {}))

(defn get-context-dirs
  []
  @context-dirs)

(defn setup-context-dirs
  [^String plugin-dir]
  (let [data-rootdir (fs/join-paths plugin-dir "data")
        env-rootdir (fs/join-paths plugin-dir "envs")
        config-rootdir (fs/join-paths plugin-dir "configs")
        cache-rootdir (fs/join-paths plugin-dir "cache")]
    (reset! context-dirs {:data-rootdir data-rootdir
                          :env-rootdir env-rootdir
                          :config-rootdir config-rootdir
                          :cache-rootdir cache-rootdir})))

;; logic for determining plugins dir -- see below
(defonce ^:private plugins-dir*
  (delay
   (let [filename @custom-plugin-dir]
     (try
        ;; attempt to create <current-dir>/plugins if it doesn't already exist. Check that the directory is readable.
       (let [path (fs/get-path filename)]
         (fs/create-dir-if-not-exists! path)
         (assert (Files/isWritable path)
                 (str "TService does not have permissions to write to plugins directory " filename))
         (setup-context-dirs (.toString path))
         path)
        ;; If we couldn't create the directory, or the directory is not writable, fall back to a temporary directory
        ;; rather than failing to launch entirely. Log instructions for what should be done to fix the problem.
       (catch Throwable e
         (log/warn
          e
          "TService cannot use the plugins directory " filename
          "\n"
          "Please make sure the directory exists and that TService has permission to write to it."
          "You can change the directory TService uses for modules by setting tservice-plugin-path variable in the edn file."
          "Falling back to a temporary directory for now.")
          ;; Check whether the fallback temporary directory is writable. If it's not, there's no way for us to
          ;; gracefully proceed here. Throw an Exception detailing the critical issues.
         (let [path (fs/get-path (System/getProperty "java.io.tmpdir"))]
           (assert (Files/isWritable path)
                   "TService cannot write to temporary directory. Please set tservice-plugin-path to a writable directory and restart Tservice.")
           (setup-context-dirs (.toString path))
           path))))))

;; Actual logic is wrapped in a delay rather than a normal function so we don't log the error messages more than once
;; in cases where we have to fall back to the system temporary directory
(defn plugins-dir
  "Get a `Path` to the TService plugins directory, creating it if needed. If it cannot be created for one reason or
  another, or if we do not have write permissions for it, use a temporary directory instead."
  ^Path []
  @plugins-dir*)

(defn get-context-path
  [^clojure.lang.Keyword cn ^String plugin-name]
  (let [cn-map {:data :data-rootdir
                :env  :env-rootdir
                :config :config-rootdir
                :cache :cache-rootdir}
        key (cn cn-map)]
    (when key
      (fs/join-paths (key (get-context-dirs)) plugin-name))))

(defn get-workdir
  ([]
   (fs/join-paths @workdir-root (u/uuid)))
  ([& {:keys [username uuid]}]
   (try
     (let [uuid (or uuid (u/uuid))
           subpath (if username (fs/join-paths username uuid) uuid)]
       (fs/join-paths @workdir-root subpath))
     (catch Exception e
       (log/error "You need to run setup-workdir-root function firstly.")))))

(defn add-env-to-path
  "Add the env directory of a plugin into PATH variable."
  {:added "0.2.0"}
  [plugin-name]
  (let [env-bin-path (fs/join-paths (get-context-path :env plugin-name) "bin")
        path (u/get-path-variable)]
    (str env-bin-path ":" path)))
