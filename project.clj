(defproject com.github.yjcyxky/tservice-core "0.1.0"
  :description "A plugin system for managing several types of plugins, such as deploying http/async/dag task."
  :url "https://github.com/yjcyxky/tservice-core"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     
                 [org.clojure/tools.logging "1.1.0"]
                 [org.clojure/tools.namespace "1.0.0"]
                 [org.clojure/core.async "0.4.500"
                  :exclusions [org.clojure/tools.reader]]

                 [prismatic/schema "1.2.0"]
                 [danlentz/clj-uuid "0.1.9"]
                 [clojure.java-time "0.3.2"]
                 [camel-snake-kebab "0.4.2"]
                 [colorize "0.1.1" :exclusions [org.clojure/clojure]]               ; string output with ANSI color codes (for logging)
                 [org.tcrawley/dynapath "1.0.0"]                                    ; Dynamically add Jars (e.g. Oracle or Vertica) to classpath

                 [selmer "1.12.27"]
                 [com.github.yjcyxky/local-fs "0.1.5"]
                 [com.github.yjcyxky/remote-fs "0.2.1"]

                 [io.forward/yaml "1.0.11" :exclusions [org.clojure/clojure]]]
  
  :test-paths ["test"]
  :plugins [[lein-cloverage "1.0.13"]
            [lein-shell "0.5.0"]
            [lein-ancient "0.6.15"]
            [lein-changelog "0.3.2"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.0"]]}}
  :deploy-repositories [["releases" :clojars]]
  :aliases {"update-readme-version" ["shell" "sed" "-i" "" "s/\\\\[com\\.github\\.yjcyxky\\\\/tservice-core \"[0-9.]*\"\\\\]/[com\\.github\\.yjcyxky\\\\/tservice-core \"${:version}\"]/" "README.md"]}
  :release-tasks [["shell" "git" "diff" "--exit-code"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["changelog" "release"]
                  ["update-readme-version"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy"]
                  ["vcs" "push"]])
