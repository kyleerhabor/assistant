(ns kyleerhabor.assistant.effect)

(defn process [effects handlers]
  (run! (fn [[on data]]
          (let [handler (get handlers on)]
            (handler data))) effects))
