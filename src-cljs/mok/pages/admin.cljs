(ns mok.pages.admin
  "Admin page"
  (:refer-clojure :exclude [partial atom flush])
  (:require
   [mok.states :refer [companylist]]
   [mok.utils :refer [make-toast date-formatter datetime-formatter loading-spinner default-error-handler
                      status-text
                      error-toast make-resp-handler get-window-width get-window-height cid->name admin? set-title! session-expired?]]
   [mok.pages.company :refer [get-companylist]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [goog.events :as events]
   [goog.history.EventType :as EventType]
   [ajax.core :refer [GET DELETE POST]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]])
  (:import [goog History]))

(defonce sec-display (atom nil))

(defn set-sec-display [ky]
  (reset! sec-display ky))

(defn cancel-buttn []
  [:a.btn-light {:href "javascript:;" :on-click #(set-sec-display nil)} "取消"])

(defn admin-page []
  (set-title! "管理员设置")
  (fn []
    [:div.admin__container
     [:div.admin__part
      [:div.admin__part-title "修改密码"]
      [:a.btn-light.admin__top-btn
       {:class (if (= @sec-display :edit-password) "hide" "show")
        :href "javascript:;" :on-click #(set-sec-display :edit-password)} "点击修改"]
      
      [:div.admin__field {:class (if (= @sec-display :edit-password) "show" "hide")}
       [:div.admin__field-row
        [:label "原密码"]
        [:input {:type :text :value "*****"}]]
       [:div.admin__field-row
        [:label "新密码"]
        [:input {:type :text :value "*****"}]]
       [:div.admin__field-row
        [:label "请再次输入新密码"]
        [:input {:type :text :value "*****"}]]
       [:div.admin__field-buttons
        [:a.btn-light {:href "javascript:;"} "确认"]
        [cancel-buttn]]]]
     [:div.admin__part
      [:div.admin__part-title "创建管理员"]
      [:a.btn-light.admin__top-btn
       {:class (if (= :create-admin @sec-display) "hide" "show")
        :href "javascript:;" :on-click #(set-sec-display :create-admin)} "点击创建"]
      [:div.admin__field {:class (if (= :create-admin @sec-display) "show" "hide")}
       [:div.admin__field-row
        [:label "用户名"]
        [:input {:type :text :value "*****"}]]
       [:div.admin__field-row
        [:label "密码"]
        [:input {:type :text :value "*****"}]]
       [:div.admin__field-row
        [:div "权限"]
        [:div.admin__right-options
         [:div
          [:label {:for "adm-user"} "用户管理"]
          [:input#adm-user {:type :checkbox}]]
         [:div
          [:label {:for "adm-push"} "推送消息"]
          [:input#adm-push {:type :checkbox}]]
         [:div
          [:label {:for "adm-fd"} "用户反馈"]
          [:input#adm-fd {:type :checkbox}]]
         [:div
          [:label {:for "adm-mblog"} "社区活动"]
          [:input#adm-mblog {:type :checkbox}]]]]
       [:div.admin__field-buttons
        [:a.btn-light {:href "javascript:;"} "确认"]
        [cancel-buttn]]]]
     [:div.admin__part
      [:div.admin__part-title "管理员列表"]
      [:a.btn-light.admin__top-btn
       {:class (if (= :show-admin @sec-display) "hide" "show")
        :href "javascript:;" :on-click #(set-sec-display :show-admin)} "点击查看"]
      [:div.admin__field {:class (if (= :show-admin @sec-display) "show" "hide")}
       [:div
        [:div "admin"] [:div "社区活动，权限列表，用户反馈，推送消息，用户管理"]]
       [:div.admin__field-buttons
        [:a.btn-light {:href "javascript:;"} "确认"]]]]]))
