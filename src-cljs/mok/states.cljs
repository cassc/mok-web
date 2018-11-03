(ns mok.states
  "Stores global states"
  (:refer-clojure :exclude [atom flush])
  (:require
   [reagent.core :as r :refer [atom]]))

(defonce companylist (atom []))
(defonce applications-store (atom nil))

(defonce me (atom nil))


(defn companyid->name [id]
  (some->> @companylist (filter #(= id (:id %))) first :name))

(defn appid->title [appid]
  (some #(when (= appid (:appid %)) (:title %)) @applications-store))


(defonce seller-list-store (atom []))
