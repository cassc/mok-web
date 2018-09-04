(ns mok.core
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [mok.utils :as u :refer [default-error-handler admin? hawks-admin? error-toast user-has-right?]]
   [mok.states :refer [me]]
   [mok.pages.company :refer [get-companylist get-all-langs]]
   [mok.pages.broadcast :refer [broadcast messages msg-queue]]
   [mok.pages.um :refer [um]]
   [mok.pages.banner :refer [banner-manage]]
   [mok.pages.feedback-manage :refer [feedback-manage]]
   [mok.pages.mblog :refer [mblog-manage]]
   [mok.pages.acts :refer [acts-manage]]
   [mok.pages.report :refer [report-manage]]
   
   [ajax.core :refer [GET POST]]
   [goog.events :as events]
   [goog.history.EventType :as EventType]
   [secretary.core :as secretary :include-macros true]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [cljs.core.async         :as a :refer [>! <! timeout chan pipe]])
  (:import [goog History]))

(defn left-navbar []
  (fn []
    [:div
     (when (user-has-right? :broadcast)
       [:div
        [:div.bkl-title.bkl-img1 "APP推送消息"]
        [:ul.bk-ul
         [:li {:class (when (= (session/get :page) :broadcast) "bk-li-active")}
          [:a {:href "#/broadcast"} "发送消息"]]
         [:li {:class (when (= (session/get :page) :messages) "bk-li-active")}
          [:a {:href "#/messages"} "历史消息"]]
         [:li {:class (when (= (session/get :page) :msg-queue) "bk-li-active")}
          [:a {:href "#/msg-queue"} "消息队列"]]]])
     [:div
      [:div.bkl-title.bkl-img2 "管理"]
      [:ul.bk-ul
       [:li {:class (when (= (session/get :page) :um) "bk-li-active")}
        [:a {:href "#/um"} "用户管理"]]
       [:li {:class (when (= (session/get :page) :feedback-manage) "bk-li-active")}
        [:a {:href "#/feedback-manage"} "用户反馈"]]
       [:li {:class (when (= (session/get :page) :mblog-manage) "bk-li-active")}
        [:a {:href "#/mblog-manage"} "社区内容管理"]]
       [:li {:class (when (= (session/get :page) :acts-manage) "bk-li-active")}
        [:a {:href "#/acts-manage"} "社区活动管理"]]
       [:li {:class (when (= (session/get :page) :banner-manage) "bk-li-active")}
        [:a {:href "#/banner-manage"} "社区Banner管理"]]
       [:li {:class (when (= (session/get :page) :report-manage) "bk-li-active")}
        [:a {:href "#/report-manage"} "举报管理"]]]]]))


(defn- get-me []
  (let [ch (chan)]
    (if @me
      (a/put! ch :done)
      (GET "/me"
           {:handler (fn [{:keys [data code]}]
                       (if (zero? code)
                         (do
                           (t/info "get-me success")
                           (reset! me data)
                           (a/put! ch :success))
                         (do
                           (reset! me nil)
                           (a/put! ch :fail))))
            :error-handler (fn [err]
                             (a/put! ch :error)
                             (reset! me nil)
                             (default-error-handler "/me" err))
            :response-format :json
            :keywords? true}))
    ch))

(defn- load-data!
  []
  (go
    (if (= :success (<! (get-me)))
      (do
        (get-companylist)
        (get-all-langs))
      (u/redirect! "/login.html"))))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; routes and data initializations

(def pages
  {:broadcast #'broadcast
   :messages #'messages
   :msg-queue #'msg-queue
   :um #'um
   :feedback-manage #'feedback-manage
   :mblog-manage #'mblog-manage
   :banner-manage #'banner-manage
   :acts-manage #'acts-manage
   :report-manage #'report-manage})

(defn page []
  [(pages (session/get :page))])

;; -------------------------
;; Routes
(secretary/set-config! :prefix "#")

;; TODO too many code repeats
(secretary/defroute "/" [] (session/put! :page :um))

(secretary/defroute "/broadcast" []
  (session/put! :page :broadcast))

(secretary/defroute "/msg-queue" []
  (session/put! :page :msg-queue))

(secretary/defroute "/messages" []
  (session/put! :page :messages))

(secretary/defroute "/feedback-manage" []
  (session/put! :page :feedback-manage))

(secretary/defroute "/mblog-manage" []
  (session/put! :page :mblog-manage))

(secretary/defroute "/um" []
  (session/put! :page :um))

(secretary/defroute "/banner-manage" []
  (session/put! :page :banner-manage))
(secretary/defroute "/acts-manage" []
  (session/put! :page :acts-manage))
(secretary/defroute "/report-manage" []
  (session/put! :page :report-manage))


;; -------------------------
;; History
;; must be called after routes have been defined
(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
     EventType/NAVIGATE
     (fn [event]
       (secretary/dispatch! (.-token event))))
    (.setEnabled true)))

;; -------------------------
;; Initialize app
;; (r/render [#'bdcategories-selector] (.getElementById js/document "bdcategories"))

(defn header []
  [:div {:class "bk_wrap"} "\t" 
   [:a {:href "#"}
    [:img {:src "images/bk_logo-haier.png", :class "bk_logo"}]]
   [:div {:class "bk_info_user"}
    [:img {:src "images/personIcon.png", :class "personIcon"}]
    [:span {:class "personName"}
     [:a {:href "/admin/editPassword"} ]]
    [:a {:href "/logout", :class "loginOut"} "退出"]]])

(defn mount-components []
  (r/render [#'header] (.getElementById js/document "bk_header"))
  (r/render [#'left-navbar] (.getElementById js/document "leftnavbar"))
  (r/render [#'page] (.getElementById js/document "app"))
  ) 

(defonce init! 
  (delay
   (load-data!)
   (hook-browser-navigation!))) 
 
(defn ^:export main []
  (t/debug "init page")
  @init!
  (mount-components))

(main)
