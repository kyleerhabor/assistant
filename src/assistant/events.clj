(ns assistant.events
  (:require [clojure.tools.logging :as log]
            [assistant.commands :refer [commands]]
            [discljord.formatting :as fmt]))

(defn options->map
  "Recursively transforms `m` to convert `:options` keys from vectors of maps to just maps."
  [m]
  (if (contains? m :options)
    (update m :options (partial reduce #(assoc %1 (keyword (:name %2)) (options->map %2)) {}))
    m))

(defmulti handler
  "Handler for Discord API events."
  (fn [_ type _] type))

(defmethod handler :interaction-create
  [conn _ interaction]
  (if (#{2 3 4} (:type interaction))
    ;; It may look like this (or ...) could be shortened to get :name once one of the two have been resolved, but do not
    ;; be fooled. The interaction data map won't have a :name key for components. The second argument could be evaluated
    ;; first, but I doubt message commands are more common than interaction commands (order of importance). Hence, :name
    ;; is checked separately.
    (if-let [name (or (:name (:data interaction))
                      (:name (:interaction (:message interaction))))]
      ((:fn ((keyword name) commands)) conn (update interaction :data options->map)))))

(defmethod handler :ready
  [_ _ data]
  (log/info (str "Connected as " (fmt/user-tag (:user data))
                 " (" (-> data :user :id) " | Shard: " (first (:shard data)) \))))

(defmethod handler :default
  [_ _ _])
