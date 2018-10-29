(ns mok.pages.um
  "User list/manage page "
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
   [ajax.core :refer [GET DELETE POST PUT]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]])
  (:import [goog History]))


(defonce userlist (atom nil))
(defonce pop-user-info (atom nil))
(defonce userstats (atom nil))
(defonce active-tab (atom :userlist))

(defn- show-tab [ky]
  (reset! active-tab ky))

(defonce default-search-params
  {:page 1
   :cnt 20
   :orderby "aid"
   :desc "desc"
   :query ""})

(defonce search-params
  (atom default-search-params))

(defonce search-state (atom {:last nil :searching nil}))

;; TODO better to switch to websocket
(defn- get-userlist [& [replace?]]
  (when-not @session-expired?
    (swap! search-state assoc :searching true)
    (GET "/users"
         {:params @search-params
          :handler (make-resp-handler
                    {:callback-success
                     #(let [data (:data %)]
                        (if replace?
                          (reset! userlist data)
                          (swap! userlist concat data))
                        (when (< (count data) 20)
                          (swap! search-state assoc :last true)))})
          :error-handler (partial default-error-handler "/users")
          :response-format :json
          :keywords? true
          :finally #(swap! search-state assoc :searching nil)})))


(defn- get-userstats []
  (when-not @session-expired?
    (GET "/umstats.json"
         {:handler (make-resp-handler
                    {:callback-success #(when-let [data (:data %)]
                                          (reset! userstats data))})
          :error-handler (partial default-error-handler "/umstats.json")
          :response-format :json
          :keywords? true})))

(defn- load-more []
  (let [wh (.height (js/$ js/document))
        pos (.scrollTop (js/$ js/document))
        h (.height (js/$ js/window))]
    (when-not (or (< (+ h pos 10) wh)  (:last @search-state) (:searching @search-state))
      (swap! search-params update-in [:page] inc)
      (get-userlist))))

(defn- order-clicked
  [key]
  (if (= key (:orderby @search-params))
    (swap! search-params (fn [old] (assoc old :page 1 :desc (if (= "desc" (:desc old)) "asc" "desc"))))
    (swap! search-params assoc :orderby key :page 1 :desc "desc"))
  (swap! search-state assoc :last nil)
  (get-userlist true))

(defn- delete-user
  "Delete account by account id"
  [aid]
  (let [uri (str "/user/" aid)]
    (when (js/confirm (str "确认删除ID为" aid "的用户？"))
      (DELETE uri
              {:handler (make-resp-handler {:callback-success (fn [_] (swap! userlist (fn [ulist] (remove #(= aid (:aid %)) ulist))))})
               :error-handler (partial default-error-handler uri)
               :response-format :json
               :keywords? true
               :finally #(swap! search-state assoc :searching nil)}))))

(defn- make-user-info-string [u]
  (reduce (fn [s key]
            (str s (when-not (s/blank? (key u))
                     (str (name key) ": " (key u)))))
          ""
          [:phone :email :qq :sina_blog :weixin]))

(defn toggle-user-info-dialog [{:keys [roles aid] :as user}]
  (swap! pop-user-info (fn [old] (when-not (= (:aid old) aid) user)))
  nil)

(defn- update-user-status-in-userlist [{:keys [aid status]}]
  (swap! userlist (fn [xs] (mapv (fn [u] (if (= (:aid u) aid) (assoc u :status status) u)) xs))))

(defn- change-user-status []
  (let [nst (:status @pop-user-info)
        params {:aid (:aid @pop-user-info) :status nst}]
    (POST "/account/status"
          {:params params
           :handler (make-resp-handler
                     {:callback-success
                      #(do
                         (swap! pop-user-info assoc :status nst)
                         (update-user-status-in-userlist params)
                         (make-toast "修改成功！"))})
           :error-handler (partial default-error-handler "/account/status")
           :response-format :json
           :format :json
           :keywords? true})))


(defn- user-info-dialog []
  (fn []
    (let [{:keys [roles phone haier status] :as u} @pop-user-info]
      [:div.alertViewBox {:style {:display :block :text-align :left}}
       [:div#user-info-dialog-0.alertViewBoxContent
        [:h4 (str (or haier phone) "的详情")]
        [:div.ct
         [:table.user-info-dialog
          [:thead
           [:tr [:th "成员"] [:th "时间"]]]
          [:tbody
           (doall
            (for [{:keys [nickname create_time]} roles]
              ^{:key create_time}
              [:tr [:td nickname] [:td create_time]]))]]
         [:div
          [:span "修改状态为"]
          [:select
           {:name "tag"
            :value status
            :on-change #(let [idx (.. % -target -selectedIndex)]
                          (swap! pop-user-info assoc :status (-> (aget (.-target %) idx) .-value))
                          (change-user-status))}
           (doall
            (for [i (range 3)]
              ^{:key i}
              [:option {:value i} (status-text i)]))]]
         [:div.companyEditShdow-i18-buttons
          [:input.alertViewBox-compilerDelete {:type :button :value "确认" :on-click #(toggle-user-info-dialog u)}]]]]])))

;; com.jianqing.btcontrol/3.0.4 (Android;MHA-AL00;8.0.0)
(defn parse-android-ua [ua]
  (let [[[_ app app-version phone phone-os phone-version]] (re-seq #"(.*?)/(.*?)\s+\((.*?);(.*?);(.*)\)?" (or ua ""))]
    (zipmap [:app :app-version :phone :phone-os :phone-version]
            [app app-version phone phone-os phone-version])))

(defn parse-ios-ua [ua]
  (let [[app mre] (s/split ua #"/")
        [app-version mre] (s/split mre #"\(")
        [phone phone-os] (s/split mre #";")]
    (zipmap [:app :app-version :phone :phone-os]
            [app app-version phone phone-os])))


(defn parse-ua [ua]
  (try
    (if (s/includes? (s/lower-case ua) "iphone")
      (parse-ios-ua ua)
      (parse-android-ua ua))
    (catch :default e
      (t/warn "ignore parse-ua error" ua e))))

(def userlist-table
  (with-meta
    (fn []
      [:div {:class (if (= :userlist @active-tab) "show" "hide")}
       [:table.manage-table {:cell-padding 0 :cell-spacing 0}
        [:thead
         [:tr
          [:th [:a {:href "javascript:;"
                    :on-click #(order-clicked "aid")}
                "aid" (when (= "aid" (:orderby @search-params)) (if (= "desc" (:desc @search-params)) " ▼" " ▲")) ]]
          [:th [:a {:href "javascript:;"
                    :on-click #(order-clicked "company_id")}
                "公司" (when (= "company_id" (:orderby @search-params)) (if (= "desc" (:desc @search-params)) " ▼" " ▲"))]]
         [:th [:span {:style {:color :grey}} "账号"]]
         [:th [:a {:href "javascript:;"
                   :on-click #(order-clicked "register_time")}
               "注册时间" (when (= "register_time" (:orderby @search-params)) (if (= "desc" (:desc @search-params)) " ▼" " ▲")) ]]
         [:th [:a {:href "javascript:;"
                   :on-click #(order-clicked "measure_time")}
               "最近测量" (when (= "measure_time" (:orderby @search-params)) (if (= "desc" (:desc @search-params)) " ▼" " ▲")) ]]
          [:th [:a {:href "javascript:;"
                    :on-click #(order-clicked "last_login")}
                "最近登录" (when (= "last_login" (:orderby @search-params)) (if (= "desc" (:desc @search-params)) " ▼" " ▲")) ]]
          [:th [:span {:style {:color :grey}} "手机型号"]]
          [:th [:span {:style {:color :grey}} "App版本"]]
          [:th [:span {:style {:color :grey}} "设备ID"]]
          [:th [:span {:style {:color :grey}} "状态"]]
          [:th [:span {:style {:color :grey}} "操作"]]]]
        [:tbody
         (doall
          (for [u @userlist
                :let [{:keys [aid company_id phone email haier qq sina_blog weixin register_time measure_time last_login roles with-device status phone phone-os phone-version app device scale ua]} u
                      company-name (cid->name company_id)
                      manual? (and measure_time (not with-device))
                      {:keys [app app-version phone phone-os phone-version]} (parse-ua ua)]]
            ^{:key aid}
            [:tr 
             [:td aid]
             [:td {:title company-name :style {:max-width "80px"}} (subs (or company-name "") 0 6)]
             [:td {:title (make-user-info-string u)} (or haier phone)]
             ;; [:td {:style {:max-width "120px"}}
             ;;  (s/join ", " (map #(str (:nickname %) "(" (:sex %) ":" (:age %) ")")  roles))]
             [:td register_time]
             [:td measure_time]
             [:td last_login]
             [:td (or phone-os "")]
             [:td app-version]
             [:td scale]
             [:td (status-text status)]
             [:td [:a {:href "javascript:;" :on-click #(toggle-user-info-dialog u)} "查看"]]]))]]
       (when @pop-user-info
         [user-info-dialog])])
    {:component-will-mount #(.addEventListener js/window "scroll" load-more)
     :component-will-unmount #(.removeEventListener js/window "scroll" load-more)}))

(def default-user {:phone ""})

(defonce user-store (atom default-user))

(defn- add-user [params]
  (PUT "/user"
       {:params params
        :format :json
        :response-format :json
        :keywords? true
        :timeout 60000
        :handler (fn [{:keys [code msg]}]
                   (if (zero? code)
                     (do
                       (js/alert "创建成功！")
                       (reset! user-store default-user)
                       (get-userlist true)
                       (show-tab :userlist))
                     (js/alert msg)))
        :error-handler default-error-handler}))

(defn user-add-field []
  [:div {:class (if (= :user-add @active-tab) "show" "hide")}
   [:div.um__row
    [:label.um__label "手机号"]
    [:input.um__input {:type :text
                       :value (:phone @user-store)
                       :on-change #(swap! user-store assoc :phone (-> % .-target .-value))}]]
   
   [:div.um__btn-group
    (when-not (s/blank? (:phone @user-store))
      [:a.btn-light {:href "javascript:;" :on-click #(add-user @user-store)} "创建"])
    [:a.btn-light {:href "javascript:;" :on-click #(show-tab :userlist)} "取消"]]])


(defn- make-age-stats-string [age-dist]
  (reduce (fn [ss {:keys [age cnt percent]}]
            (str ss age "岁 : " cnt "人 (" (subs (str percent) 0 4) "%) " " \n"))
          ""
          (take 20 (reverse (sort-by :percent age-dist)))))

(defn userstats-widget
  []
  [:div.user_statistics
   [:p.us_title "用户概况"]
   (doall
    (for [stat @userstats]
      ^{:key (str (Math/random))}
      [:div.user_statistics_wrap
       [:div.us_companyName
        [:p.us_companyName_title (str (-> stat :company :name) "(" (-> stat :company :id) ")") ]
        (if (pos? (-> stat :users :all))
          [:ul.us_companyName_ul
           [:li [:span [:a {:title (make-age-stats-string (-> stat :users :age-stats)) :href "javascript:;"} "年龄占比前20名" [:i.fa.fa-hand-paper-o]]]]
           [:li (str "总用户数：" (-> stat :users :all))]
           [:li (str "设备用户数：" (-> stat :users :wm))]
           [:li (str "周活跃用户数：" (-> stat :week-cnt))]
           [:li (str "月活跃用户数：" (-> stat :month-cnt))]]
          [:p "无用户"])]]))])

(defn um []
  (set-title! "用户管理")
  (when-not @userlist
    (get-userlist true))
  (when-not @companylist
    (get-companylist))
  (when (not @userstats)
    (get-userstats))
  (fn []
    [:div.id.bkcr-content
     [userstats-widget]
     [:p.bkcrc-title
      [:span "管理"]
      "  >  "
      [:span.bkcrc-seceondT "用户管理"]]
     [:div.um__head-opts {:class (if (= :userlist @active-tab) "show" "hide")}
      [:input {:type :text :placeholder "按手机号或aid过滤" :value (:query @search-params)
               :on-change #(let [phone (-> % .-target .-value s/trim)]
                             (swap! search-params assoc :query phone)
                             (cond
                               (> (count phone) 2) (do
                                                     (swap! search-params assoc :page 1)
                                                     (get-userlist true))
                               (s/blank? phone) (do
                                                  (swap! search-params assoc :query "" :page 1)
                                                  (get-userlist true))
                               :else nil))}]
      [:a.btn-light.um__add-user {:href "javascript:;" :on-click #(show-tab :user-add)} "添加用户"]]
     [userlist-table]
     [user-add-field]
     [loading-spinner (:searching @search-state)]]))
