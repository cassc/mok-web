(ns mok.core
  (:refer-clojure :exclude [partial atom flush])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
   [mok.utils :as u :refer [default-error-handler admin? hawks-admin? error-toast user-has-right?]]
   [mok.states :refer [me]]
   [mok.pages.company :refer [get-companylist get-all-langs]]
   [mok.pages.broadcast :refer [broadcast messages msg-queue]]
   [mok.pages.um :refer [um]]
   [mok.pages.video :refer [videos-page]]
   [mok.pages.seller :refer [seller-manage]]
   [mok.pages.prod :refer [prod-manage]]
   [mok.pages.order :refer [order-manage order-return-manage]]
   [mok.pages.admin :refer [admin-page]]
   [mok.pages.banner :refer [banner-manage]]
   [mok.pages.feedback-manage :refer [feedback-manage]]
   [mok.pages.mblog :refer [mblog-manage]]
   [mok.pages.acts :refer [acts-manage]]
   [mok.pages.report :refer [report-manage]]
   
   [ajax.core :refer [GET]]
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
     (when (or
            (user-has-right? :usermanage)
            (user-has-right? :feeback)
            (user-has-right? :mblog))
       [:div
        [:div.bkl-title.bkl-img2 "管理"]
        [:ul.bk-ul
         (when (user-has-right? :usermanage)
           [:li {:class (when (= (session/get :page) :um) "bk-li-active")}
            [:a {:href "#/um"} "用户管理"]])
         (when (user-has-right? :feedback)
           [:li {:class (when (= (session/get :page) :feedback-manage) "bk-li-active")}
            [:a {:href "#/feedback-manage"} "用户反馈"]])
         (when (user-has-right? :mblog)
           [:li {:class (when (= (session/get :page) :mblog-manage) "bk-li-active")}
            [:a {:href "#/mblog-manage"} "社区内容管理"]])
         (when (user-has-right? :mblog)
           [:li {:class (when (= (session/get :page) :acts-manage) "bk-li-active")}
            [:a {:href "#/acts-manage"} "社区活动管理"]])
         (when (user-has-right? :mblog)
           [:li {:class (when (= (session/get :page) :banner-manage) "bk-li-active")}
            [:a {:href "#/banner-manage"} "社区Banner管理"]])
         (when (user-has-right? :mblog)
           [:li {:class (when (= (session/get :page) :report-manage) "bk-li-active")}
            [:a {:href "#/report-manage"} "举报管理"]])
         ;; (when (user-has-right? :mblog)
         ;;   [:li {:class (when (= (session/get :page) :videos) "bk-li-active")}
         ;;    [:a {:href "#/videos"} "视频课程"]])
         ]])
     (when (user-has-right? :shop)
       [:div
        [:div.bkl-title.bkl-img2 "商城"]
        [:ul.bk-ul
         [:li {:class (when (= (session/get :page) :shop) "bk-li-active")}
          [:a {:href "#/seller"} "卖家管理"]]
         [:li {:class (when (= (session/get :page) :prod) "bk-li-active")}
          [:a {:href "#/prod"} "商品管理"]]
         [:li {:class (when (= (session/get :page) :order) "bk-li-active")}
          [:a {:href "#/order"} "订单发货"]]
         [:li {:class (when (= (session/get :page) :order-return) "bk-li-active")}
          [:a {:href "#/order-return"} "退换货"]]]])]))

(defn task-select-pane []
  [:div.bkcr-content
   [:h3 "您好，" (or
                  (:email @me)
                  (:username @me)) "！" ]
   [:div "欢迎使用海尔健康平台内部管理系统，请选择左侧的菜单开始。"]])

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
   :seller #'seller-manage
   :prod #'prod-manage
   :order #'order-manage
   :order-return #'order-return-manage
   :admin #'admin-page
   :feedback-manage #'feedback-manage
   :mblog-manage #'mblog-manage
   :banner-manage #'banner-manage
   :acts-manage #'acts-manage
   :report-manage #'report-manage
   :videos #'videos-page
   :task-select-pane #'task-select-pane})

(defn page []
  [(pages (session/get :page))])

;; -------------------------
;; Routes
(secretary/set-config! :prefix "#")


(secretary/defroute "/" []
  (cond
    (user-has-right? :usermanage) (session/put! :page :um)
    (user-has-right? :feedback) (session/put! :page :feedback-manage)
    (user-has-right? :shop) (session/put! :page :shop)
    (user-has-right? :broadcast) (session/put! :page :broadcast)
    (user-has-right? :mblog) (session/put! :page :mblog-manage)
    :else (session/put! :page :task-select-pane)))

(secretary/defroute "/broadcast" []
  (session/put! :page :broadcast))

(secretary/defroute "/videos" []
  (session/put! :page :videos))

(secretary/defroute "/msg-queue" []
  (session/put! :page :msg-queue))

(secretary/defroute "/messages" []
  (session/put! :page :messages))

(secretary/defroute "/feedback-manage" []
  (session/put! :page :feedback-manage))

(secretary/defroute "/mblog-manage" []
  (session/put! :page :mblog-manage))

(secretary/defroute "/prod" []
  (session/put! :page :prod))
(secretary/defroute "/seller" []
  (session/put! :page :seller))
(secretary/defroute "/order" [] (session/put! :page :order))
(secretary/defroute "/order-return" [] (session/put! :page :order-return))

(secretary/defroute "/um" []
  (session/put! :page :um))

(secretary/defroute "/admin" []
  (session/put! :page :admin))

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
   [:div.bk_info_user.header__right-links
    (when (admin?)
      [:a.header__admin-link {:href "#/admin"} [:span "控制台"]])
    [:a {:href "/logout"} "退出"]]])

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
