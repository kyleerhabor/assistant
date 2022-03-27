(ns assistant.bot.interaction.util)

(def ephemeral (bit-shift-left 1 6))
(def max-autocomplete-choices 25)
(def max-autocomplete-name-length 100)
(def max-embed-description-length 4096)
(def component-types
  "https://discord.com/developers/docs/interactions/message-components#component-object-component-types"
  {:action-row 1
   :button 2
   :select-menu 3
   :text-input 4})

;; From 16 to 4096
(def image-sizes (map #(long (Math/pow 2 %)) (range 4 13)))
