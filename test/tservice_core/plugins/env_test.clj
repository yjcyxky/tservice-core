(ns tservice-core.plugins.env-test
  (:require [clojure.test :refer [use-fixtures deftest is testing]]
            [tservice-core.plugins.env :as env]
            [clojure.string :as clj-str]))

(use-fixtures :once
  (fn [f]
    (env/setup-plugin-dir "/tmp")
    (env/setup-workdir-root "/tmp")
    (f)))

(deftest test-env
  (testing "Test the environment."
    (is (= "/tmp" (env/setup-workdir-root "/tmp")))
    (is (= "/tmp" (env/setup-plugin-dir "/tmp")))
    (is (= (env/get-workdir-root) (env/setup-workdir-root "/tmp")))
    (is (= (env/get-plugin-dir) (env/setup-plugin-dir "/tmp")))

    (is (= "/tmp" (.toString (env/plugins-dir))))
    (is (= (env/get-context-dirs) (env/setup-context-dirs "/tmp")))))

(deftest test-get-workdir
  (testing "Test the working directory."
    (is (= "/tmp/xxx" (env/get-workdir :uuid "xxx")))
    (is (= "/tmp/username/xxx" (env/get-workdir :uuid "xxx" :username "username")))
    (is (= "/tmp/xxx" (env/get-workdir :uuid "xxx" :username nil)))
    (is (clj-str/starts-with? (env/get-workdir :uuid nil :username "username") "/tmp/username/"))
    (is (clj-str/starts-with? (env/get-workdir :uuid nil :username nil) "/tmp/"))))
