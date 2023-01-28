(ns kyleerhabor.assistant.database
  (:require
    [kyleerhabor.assistant.config :refer [config]]
    [duratom.core :refer [duratom]]
    [mount.core :refer [defstate]]))

(def default-path "db.data")

(defstate db
  ;; Duratom implements Closable, which *may* delete the file afterwards. I'm not sure of this, however, and on my
  ;; laptop, it hasn't been deleted. Look into more?
  :start (duratom :local-file
           :file-path (or (::path config) default-path)))
