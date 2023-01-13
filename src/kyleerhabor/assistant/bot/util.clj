(ns kyleerhabor.assistant.bot.util
  (:require [discljord.cdn :as cdn]))

(defn user [m]
  (:user (or (:member m) m)))

(defn avatar-url
  ([user] (cdn/effective-user-avatar user)) ; No ?size=... parameter!
  ([user size]
   (cdn/resize (cdn/effective-user-avatar user) size)))
