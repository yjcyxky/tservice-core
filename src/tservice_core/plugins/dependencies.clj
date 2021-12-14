(ns tservice-core.plugins.dependencies
  (:require [clojure.tools.logging :as log]
            [tservice-core.plugins.classloader :as classloader]
            [tservice-core.plugins.util :as u]))

(def ^:private plugins-with-unsatisfied-deps
  (atom #{}))

(defn- dependency-type [{classname :class, plugin :plugin}]
  (cond
    classname :class
    plugin    :plugin
    :else     :unknown))

(defmulti ^:private dependency-satisfied?
  {:arglists '([initialized-plugin-names info dependency])}
  (fn [_ _ dep] (dependency-type dep)))

(defmethod dependency-satisfied? :default [_ {{plugin-name :name} :info} dep]
  (log/error
   (u/format-color 'red
                   (format "Plugin %s declares a dependency that TServuce does not understand: %s" plugin-name dep))
   "Refer to the plugin manifest reference for a complete list of valid plugin dependencies")
  false)

(defonce ^:private already-logged (atom #{}))

(defn log-once
  "Log a message a single time, such as warning that a plugin cannot be initialized because of required dependencies.
   Subsequent calls with duplicate messages are automatically ignored."
  {:style/indent 1}
  ([message]
   (log-once nil message))

  ([plugin-name-or-nil message]
   (let [k [plugin-name-or-nil message]]
     (when-not (contains? @already-logged k)
       (swap! already-logged conj k)
       (log/info message)))))

(defn- warn-about-required-dependencies [plugin-name message]
  (log-once plugin-name
            (str (u/format-color 'red (format "TService cannot initialize plugin %s due to required dependencies." plugin-name))
                 " "
                 message)))

(defmethod dependency-satisfied? :class
  [_ {{plugin-name :name} :info} {^String classname :class, message :message, :as dep}]
  (try
    (Class/forName classname false (classloader/the-classloader))
    (catch ClassNotFoundException _
      (warn-about-required-dependencies plugin-name (or message (format "Class not found: %s" classname)))
      false)))

(defmethod dependency-satisfied? :plugin
  [initialized-plugin-names {{plugin-name :name} :info, :as info} {dep-plugin-name :plugin}]
  (log-once plugin-name (format "Plugin ''%s'' depends on plugin ''%s''" plugin-name dep-plugin-name))
  ((set initialized-plugin-names) dep-plugin-name))

(defn- all-dependencies-satisfied?*
  [initialized-plugin-names {:keys [dependencies], {plugin-name :name} :info, :as info}]
  (let [dep-satisfied? (fn [dep]
                         (u/prog1 (dependency-satisfied? initialized-plugin-names info dep)
                                  (log-once plugin-name
                                            (format "%s dependency %s satisfied? %s" plugin-name (dissoc dep :message) (boolean <>)))))]
    (every? dep-satisfied? dependencies)))

(defn all-dependencies-satisfied?
  "Check whether all dependencies are satisfied for a plugin; return truthy if all are; otherwise log explanations about
   why they are not, and return falsey.
   For plugins that *might* have their dependencies satisfied in the near future"
  [initialized-plugin-names info]
  (or
   (all-dependencies-satisfied?* initialized-plugin-names info)

   (do
     (swap! plugins-with-unsatisfied-deps conj info)
     (log-once (u/format-color 'yellow
                               (format "Plugins with unsatisfied deps: %s" (mapv (comp :name :info) @plugins-with-unsatisfied-deps))))
     false)))


(defn- remove-plugins-with-satisfied-deps [plugins initialized-plugin-names ready-for-init-atom]
  ;; since `remove-plugins-with-satisfied-deps` could theoretically be called multiple times we need to reset the atom
  ;; used to return the plugins ready for init so we don't accidentally include something in there twice etc.
  (reset! ready-for-init-atom nil)
  (set
   (for [info  plugins
         :let  [ready? (when (all-dependencies-satisfied?* initialized-plugin-names info)
                         (swap! ready-for-init-atom conj info))]
         :when (not ready?)]
     info)))

(defn update-unsatisfied-deps!
  "Updates internal list of plugins that still have unmet dependencies; returns sequence of plugin infos for all plugins
   that are now ready for initialization."
  [initialized-plugin-names]
  (let [ready-for-init (atom nil)]
    (swap! plugins-with-unsatisfied-deps remove-plugins-with-satisfied-deps initialized-plugin-names ready-for-init)
    @ready-for-init))