(ns kyleerhabor.assistant.effect
  (:refer-clojure :exclude [update])
  (:require [clojure.core :as c]))

(defn process [effects handlers]
  (run! (fn [[on data]]
          (let [handler (get handlers on)]
            (handler data))) effects))

(defn update [effect f]
  (c/update effect 1 f))
