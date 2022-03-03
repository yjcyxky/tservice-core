(ns tservice-core.tasks.dag
  (:require [daguerreo.core :as dag]
            [daguerreo.helpers :as helpers]))

(def validate-tasks 
  ^{:added "0.2.2"}
  dag/validate-tasks)
(def cancel 
  ^{:added "0.2.2"}
  dag/cancel)
(def run 
  ^{:added "0.2.2"}
  dag/run)
(def event-logger 
  ^{:added "0.2.2"}
  helpers/event-logger)

;; It's a MultiMethod, more details on https://github.com/schmee/daguerreo/blob/master/src/daguerreo/helpers.clj
(def log-event 
  ^{:added "0.2.2"}
  helpers/log-event)