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

(defn disnum [discrim]
  (mod discrim 5))

(defn cfile
  ([k] (cfile k :format))
  ([k fmt]
   ["/" k "." fmt]))

(def endpoints {:user-avatar (concat ["/avatars/" :id] (cfile :avatar))
                :default-user-avatar (concat ["/embed/avatars"] (cfile :number))
                :user-banner (concat ["/banners/" :id] (cfile :banner))
                :guild-member-avatar (concat ["/guilds/" :guild-id "/users/" :user-id "/avatars"] (cfile :avatar))})

(defn path [path m]
  (apply str (map #(if (string? %) % (% m)) path)))
