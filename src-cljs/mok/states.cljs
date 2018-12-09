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

(defonce aliyun-oss-config {:endpoint "https://oss-cn-beijing.aliyuncs.com" ;; "https://oss-cn-beijing-internal.aliyuncs.com"
                            :bucket-name "jianqing"
                            :access-key-id "LTAIB6Qlvy4dylDW"
                            :access-key-secret "iIWX9MXK84ktnfJlnFJfET1k6gWeBW"
                            :base-url "https://jianqing.oss-cn-beijing.aliyuncs.com/"})

(defn get-oss []
  (js/OSS
   (clj->js
    {:endpoint "https://oss-cn-beijing.aliyuncs.com"
     :region "oss-cn-beijing"
     :bucket "jianqing"
     :accessKeyId "LTAIB6Qlvy4dylDW"
     :accessKeySecret "iIWX9MXK84ktnfJlnFJfET1k6gWeBW"})))



(def jianqing-oss-base "https://jianqing.oss-cn-beijing.aliyuncs.com/")
