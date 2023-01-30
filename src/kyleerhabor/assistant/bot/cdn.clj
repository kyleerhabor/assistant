(ns kyleerhabor.assistant.bot.cdn
  (:refer-clojure :exclude [format])
  (:require [discljord.cdn :as dcdn]))

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
  ([id avatar] (user-avatar id avatar (format avatar)))
  ([id avatar format]
   (str "/avatars/" id \/ (file avatar format))))

(defn disnum [discrim]
  (mod (parse-long discrim) 5))

(defn default-user-avatar
  ([n] (default-user-avatar n default-format))
  ([n format]
   (str "/embed/avatars/" (file n format))))
