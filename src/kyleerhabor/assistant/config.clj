(ns kyleerhabor.assistant.config
  (:require
   [cprop.core :refer [load-config]]
   [cprop.source :refer [from-env]]
   [mount.core :refer [defstate]]))

(defstate config
  :start (load-config :merge [(from-env)]))
