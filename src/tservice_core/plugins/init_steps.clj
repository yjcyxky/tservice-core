(ns tservice-core.plugins.init-steps
  "Logic for performing the `init-steps` listed in a TService plugin's manifest. For plugins that specify that we
   should `lazy-load`, these steps are lazily performed the first time non-trivial plugin methods (such as connecting
   to a Database) are called; for all other TService plugins these are perfomed during launch.
   The entire list of possible init steps is below, as impls for the `do-init-step!` multimethod."
  (:require [clojure.tools.logging :as log]
            [tservice-core.plugins.classloader :as classloader]
            [tservice-core.plugins.plugin-proxy :as plugin-proxy]
            [tservice-core.plugins.env :refer [get-plugin-config]]
            [tservice-core.plugins.util :as u]
            [local-fs.core :as fs]
            [clojure.string :as clj-str]))

(defmulti ^:private do-init-step!
  "Perform a plugin init step. Steps are listed in `init:` in the plugin manifest; impls for each step are found below
   by dispatching off the value of `step:` for each step. Other properties specified for that step are passed as a map."
  {:arglists '([m])}
  (comp keyword :step))

(defmethod do-init-step! :unpack-env [{envname :envname envtype :envtype postunpack :postunpack context :context}]
  (let [{:keys [jar-path plugin-name env-dest-dir env-dir config-dir data-dir]} context
        post-unpack-cmd (when postunpack
                          (u/render-template postunpack
                                             {:JAR_PATH jar-path
                                              :PLUGIN_NAME plugin-name
                                              :ENV_DEST_DIR env-dest-dir
                                              :ENV_DIR env-dir
                                              :CONFIG_DIR config-dir
                                              :DATA_DIR data-dir
                                              :ENV_NAME envname}))]
    (log/info (u/format-color 'blue (format "Unpack the environment into %s..." env-dir)))
    (when jar-path
      ;; file (Archive or common file) or directory
      (cond
        (= envtype "environment")
        (fs/extract-env-from-archive jar-path envname env-dir)

        (= envtype "configuration")
        (fs/extract-env-from-archive jar-path envname config-dir)

        (= envtype "data")
        (fs/extract-env-from-archive jar-path envname data-dir))

      (when post-unpack-cmd
        (log/info (u/format-color 'blue (format "Run post-unpack-cmd: %s" post-unpack-cmd)))
        (log/info (u/call-command! post-unpack-cmd))))))

(defmethod do-init-step! :load-namespace [{nmspace :namespace}]
  (log/info (u/format-color 'blue (format "Loading plugin namespace %s..." nmspace)))
  (classloader/require (symbol nmspace)))

(defmethod do-init-step! :register-plugin [{entrypoint :entrypoint context :context}]
  (plugin-proxy/load-and-register-plugin-metadata! entrypoint (:plugin-info context)))

(defmethod do-init-step! :init-event [{entrypoint :entrypoint}]
  (plugin-proxy/init-event! entrypoint))

(defmethod do-init-step! :init-plugin [{entrypoint :entrypoint context :context}]
  (plugin-proxy/init-plugin! entrypoint :config (get-plugin-config (:plugin-name context))))

(defn do-init-steps!
  "Perform the initialization steps for a TService plugin as specified under `init:` in its plugin
   manifest (`tservice-plugin.yaml`) by calling `do-init-step!` for each step."
  [init-steps context]
  (doseq [step init-steps]
    ;; step --> step dict in yaml
    (do-init-step! (assoc step :context context))))
