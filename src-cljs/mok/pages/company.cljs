(ns mok.pages.company
  "Company management page "
  (:refer-clojure :exclude [partial atom flush])
  (:require
   [mok.states :refer [companylist]]
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner admin? default-error-handler error-toast make-resp-handler *toast-clear* set-title! spacer]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [goog.events :as events]
   [goog.history.EventType :as EventType]
   [ajax.core :refer [GET POST PUT DELETE]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]])
  (:import [goog History]))

(defonce loading? (atom false)) ;; the loading spinner

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; company states

;; all languages available
(defonce all-langs (atom {}))

;; products of cid
(defonce products-of (atom {}))

(defn- get-products-of
  [cid]
  (GET (str "/productsof/" cid)
       {:handler #(swap! products-of assoc cid (or (:data %) []))
        :error-handler (partial default-error-handler "/productsof")
        :response-format :json
        :keywords? true}))

(defn- get-all-langs
  []
  (GET "/languages"
       {:handler #(reset! all-langs (:data %))
        :error-handler (partial default-error-handler "/languages")
        :response-format :json
        :keywords? true}))

;; state for editing/creating company
(defonce company-state (atom nil))

;; company-admin password/phone editing/creating
(defonce cadmin-state (atom nil))

;; company internationaliztion editing
(defonce clan-state (atom nil))
(defonce clan-selector (atom nil))

;; tracks sms send time
(defonce timer-state (atom {}))

;; new product state
(defonce product-state (atom nil))

(defn- get-companylist
  []
  (GET (str "/companylist")
       {:handler (make-resp-handler {:callback-success #(reset! companylist (:data %))})
        :error-handler (partial default-error-handler "/companylist")
        :response-format :json
        :keywords? true}))













