(ns kyleerhabor.assistant.bot.cdn
  (:refer-clojure :exclude [format])
  (:require
    [clojure.string :as str]
    [discljord.cdn :as dcdn]))

;;; discljord.cdn doesn't let you control some aspects like the file extension, so this file reimplements many methods
;;; to be more customizable.

(def default-format :png)

(defn file [x format]
  (str x "." (name format)))

(defn format
  ([hash] (format hash default-format))
  ([hash default]
   (if (dcdn/animated? hash)
     :gif
     default)))

(defn user-avatar
  ([data] (user-avatar data (format (:avatar data))))
  ([data format]
   (str "/avatars/" (:id data) \/ (file (:avatar data) format))))

(defn default-user-avatar
  ([data] (default-user-avatar data default-format))
  ([data format]
   (str "/embed/avatars/" (file (mod (parse-long (:discriminator data)) 5) format))))

(comment
  (require
    '[kyleerhabor.assistant.bot :as bot]
    '[discljord.messaging :as msg])

  (def user @(msg/get-user! (:messaging bot/conn) "345539839393005579"))
  
  (user-avatar user)
  (user-avatar user :gif)
  (user-avatar user :webp)
  (default-user-avatar user))
