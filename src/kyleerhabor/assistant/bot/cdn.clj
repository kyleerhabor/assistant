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
  ([id avatar] (user-avatar+ id avatar (format avatar)))
  ([id avatar format]
   (str "/avatars/" id \/ (file avatar format))))

(defn disnum [discrim]
  (mod (parse-long discrim) 5))

(defn default-user-avatar
  ([n] (default-user-avatar n default-format))
  ([n format]
   (str "/embed/avatars/" (file n format))))

(comment
  (require
    '[kyleerhabor.assistant.bot :as bot]
    '[discljord.messaging :as msg])

  (def user @(msg/get-user! (:messaging bot/conn) "345539839393005579"))
  
  (user-avatar user)
  (user-avatar user :gif)
  (user-avatar user :webp)
  (default-user-avatar user))
