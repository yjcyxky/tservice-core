(ns tservice-core.util
  "Common utility functions useful throughout the codebase."
  (:require [clojure.tools.namespace.find :as ns-find]
            [colorize.core :as colorize]
            [clojure.java.classpath :as classpath]
            [tservice-core.plugins.classloader :as classloader]
            [clj-uuid :as uuid]
            [clojure.string :as clj-str]
            [local-fs.core :as fs]
            [selmer.parser :as parser]
            [clojure.tools.logging :as log]
            [clj-time.coerce :as coerce]
            [clj-time.core :as t]
            [clojure.java.shell :as shell :refer [sh]]))

(defn- namespace-symbs* []
  (for [ns-symb (distinct
                 (ns-find/find-namespaces (concat (classpath/system-classpath)
                                                  (classpath/classpath (classloader/the-classloader)))))
        :when   (and (.startsWith (name ns-symb) "tservice.")
                     (not (.contains (name ns-symb) "test")))]
    ns-symb))

(def tservice-namespace-symbols
  "Delay to a vector of symbols of all tservice namespaces, excluding test namespaces.
    This is intended for use by various routines that load related namespaces, such as task and events initialization.
    Using `ns-find/find-namespaces` is fairly slow, and can take as much as half a second to iterate over the thousand
    or so namespaces that are part of the tservice project; use this instead for a massive performance increase."
  ;; We want to give JARs in the ./plugins directory a chance to load. At one point we have this as a future so it
  ;; start looking for things in the background while other stuff is happening but that meant plugins couldn't
  ;; introduce new tservice namespaces such as plugins.
  (delay (vec (namespace-symbs*))))

(def ^:private ^{:arglists '([color-symb x])} colorize
  "Colorize string `x` with the function matching `color` symbol or keyword."
  (fn [color x]
    (colorize/color (keyword color) x)))

(defn format-color
  "Like `format`, but colorizes the output. `color` should be a symbol or keyword like `green`, `red`, `yellow`, `blue`,
  `cyan`, `magenta`, etc. See the entire list of avaliable
  colors [here](https://github.com/ibdknox/colorize/blob/master/src/colorize/core.clj).

      (format-color :red \"Fatal error: %s\" error-message)"
  {:style/indent 2}
  (^String [color x]
   {:pre [((some-fn symbol? keyword?) color)]}
   (colorize color (str x)))

  (^String [color format-string & args]
   (colorize color (apply format (str format-string) args))))

(defn one-or-many
  "Wraps a single element in a sequence; returns sequences as-is. In lots of situations we'd like to accept either a
  single value or a collection of values as an argument to a function, and then loop over them; rather than repeat
  logic to check whether something is a collection and wrap if not everywhere, this utility function is provided for
  your convenience.
    (u/one-or-many 1)     ; -> [1]
    (u/one-or-many [1 2]) ; -> [1 2]"
  [arg]
  (if ((some-fn sequential? set? nil?) arg)
    arg
    [arg]))

(defmacro varargs
  "Make a properly-tagged Java interop varargs argument. This is basically the same as `into-array` but properly tags
  the result.
    (u/varargs String)
    (u/varargs String [\"A\" \"B\"])"
  {:style/indent 1, :arglists '([klass] [klass xs])}
  [klass & [objects]]
  (vary-meta `(into-array ~klass ~objects)
             assoc :tag (format "[L%s;" (.getCanonicalName ^Class (ns-resolve *ns* klass)))))

(defmacro prog1
  "Execute `first-form`, then any other expressions in `body`, presumably for side-effects; return the result of
   `first-form`.
    (def numbers (atom []))
    (defn find-or-add [n]
      (or (first-index-satisfying (partial = n) @numbers)
          (prog1 (count @numbers)
            (swap! numbers conj n))))
    (find-or-add 100) -> 0
    (find-or-add 200) -> 1
    (find-or-add 100) -> 0
   The result of `first-form` is bound to the anaphor `<>`, which is convenient for logging:
     (prog1 (some-expression)
       (println \"RESULTS:\" <>))
   `prog1` is an anaphoric version of the traditional macro of the same name in
   [Emacs Lisp](http://www.gnu.org/software/emacs/manual/html_node/elisp/Sequencing.html#index-prog1)
   and [Common Lisp](http://www.lispworks.com/documentation/HyperSpec/Body/m_prog1c.htm#prog1).
   Style note: Prefer `doto` when appropriate, e.g. when dealing with Java objects.
  "
  {:style/indent :defn}
  [first-form & body]
  `(let [~'<> ~first-form]
     ~@body
     ~'<>))

(defn uuid
  "These UUID's will be guaranteed to be unique and thread-safe regardless of clock precision or degree of concurrency."
  []
  (str (uuid/v1)))

(defn time->int
  [datetime]
  (coerce/to-long datetime))

(defn datetime
  []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")
           (new java.util.Date)))

(defn now
  "Get the current local datetime."
  ([offset]
   (t/to-time-zone (t/now) (t/time-zone-for-offset offset)))
  ([] (now 0)))

(defn get-path-variable
  []
  (System/getenv "PATH"))

(defn hashmap->parameters
  "{ '-d' 'true' '-o' 'output' } -> '-d true -o output'"
  [coll]
  (clj-str/join " " (map #(clj-str/join " " %) (into [] coll))))

(defmacro with-sh-env
  "Sets the directory for use with sh, see sh for details."
  {:added "0.5.6"}
  [dir env & forms]
  `(binding [shell/*sh-dir* ~dir
             shell/*sh-env* ~env]
     ~@forms))

(defn call-command!
  ([cmd parameters-coll workdir env]
   (with-sh-env workdir (merge {:PATH   (get-path-variable)
                                :LC_ALL "en_US.utf-8"
                                :LANG   "en_US.utf-8"
                                :HOME   (System/getenv "HOME")}
                               env)
     (let [command ["bash" "-c" (format "%s %s" cmd (hashmap->parameters parameters-coll))]
           result (apply sh command)
           status (if (= (:exit result) 0) "Success" "Error")
           msg (str (:out result) "\n" (:err result))]
       (log/info (format "Running the Command: %s (Environment: %s; Working Directory: %s; Status: %s; Msg: %s)"
                         command env workdir
                         status msg))
       {:status status
        :msg msg})))
  ([cmd workdir env]
   (call-command! cmd [] workdir env))
  ([cmd env]
   (call-command! cmd [] (System/getenv "PWD") env))
  ([cmd]
   (call-command! cmd [] (System/getenv "PWD") {})))

(defn render-template
  "TODO: Schema for rendering environment.
   
   Arguments:
     env-context: {:ENV_DEST_DIR \" \"
                   :ENV_NAME \"pgi\"
                   :CLONE_ENV_BIN \"\"}
   "
  [template env-context]
  (log/debug (format "Render Template with Environment Context: %s" env-context))
  (let [{:keys [ENV_DEST_DIR ENV_NAME CONFIG_DIR DATA_DIR]} env-context
        env-dest-dir (fs/join-paths ENV_DEST_DIR ENV_NAME)
        env-name ENV_NAME
        clone-env-bin (fs/which "clone-env")]
    (parser/render template {:ENV_DEST_DIR env-dest-dir
                             :CONFIG_DIR CONFIG_DIR
                             :DATA_DIR DATA_DIR
                             :ENV_NAME env-name
                             :CLONE_ENV_BIN clone-env-bin})))

(defn make-plugin-subpath
  "Return a new directory based on plugin-name and custom subdir."
  ^String [^String plugin-dir ^String dir-name ^String plugin-name]
  (fs/join-paths plugin-dir dir-name plugin-name))
