(ns mok.pages.um
  "User list/manage page

  JM03F40AA5K000ZBL
  JM03F40AA是幻彩C3（白）的物料号；5K 指的是这个码可以兑换5000积分；000代表我们第几次编写兑换码；剩下三位是随机数。"
  (:refer-clojure :exclude [partial atom flush])
  (:require
   [mok.states :refer [companylist]]
   [mok.utils :as utils :refer [make-toast date-formatter datetime-formatter loading-spinner default-error-handler
                                status-text upload-static-file
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
(defonce msg-store (atom {}))
(defonce pop-msg-info (atom nil)) ;; app msg
(defonce userstats (atom nil))
(defonce active-tab (atom :userlist))
(def default-jifen-state {:phone "" :code "" :score ""})
(defonce jifen-state (atom default-jifen-state))
(defonce jifen-code-list (atom []))
(defonce request-jifen-code-list (atom []))
(defonce active-jifen-panel (atom nil))

(defn- show-tab [ky]
  (reset! active-tab ky))

(defn- get-n-coupon-auto-confirming
  ([]
   (get-n-coupon-auto-confirming @request-jifen-code-list))
  ([n]
   (count
    (filter (fn [ts]
              (> (- (.getTime (now)) ts)
                 (* 8 3600 24 1000)))
            n))))

(add-watch request-jifen-code-list :notify-when-coupon-confirming
           (fn [k r o n]
             (let [cnt (get-n-coupon-auto-confirming n)]
               (when (pos? cnt)
                 (make-toast :warn (str "有" cnt "个兑换码申请将在两天内自动确认！"))))))

(defonce default-search-params
  {:page 1
   :cnt 20
   :orderby "aid"
   :desc "desc"
   :query ""})

(defonce search-params
  (atom default-search-params))

(defonce search-state (atom {:last nil :searching nil}))

(defn- ts-to-confirm-date [ts]
  (subs (utils/ts->readable-time (+ ts (* 10 3600 24 1000))) 0 10))

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

(defn load-req-jifen-list []
  (GET "/jifen/coupon"
       {:handler (make-resp-handler
                  {:callback-success
                   #(reset! request-jifen-code-list (:data %))})
        :error-handler (partial default-error-handler "/jifen/coupon")
        :response-format :json
        :keywords? true}))

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

(defn valid-msg? [{:keys [title msg]}]
  (not (or (s/blank? title)
           (s/blank? msg))))

(defn- send-app-msg-to [{:keys [aid]} msg]
  {:pre [aid msg]}
  (if (valid-msg? msg)
    (PUT "/broadcast/app/msg"
         {:params (assoc msg :aid aid)
          :handler (make-resp-handler
                    {:callback-success
                     (fn [{:keys [data]}]
                       (swap! pop-msg-info update-in [:v-msg] (fn [xs] (cons data xs)))
                       (reset! msg-store {})
                       (make-toast "发送成功！"))})
          :error-handler default-error-handler
          :response-format :json
          :format :json
          :keywords? true})
    (js/alert "标题及内容不能为空！")))

(defn- broadcast-app-msg [{:keys [jpush?] :as msg}]
  (if (valid-msg? msg)
    (PUT "/broadcast/app/msg"

         {:params (assoc msg :aid 0)
          :handler (make-resp-handler
                    {:callback-success
                     (fn [{:keys [data]}]
                       (reset! msg-store {})
                       (make-toast "发送成功！"))})
          :error-handler default-error-handler
          :response-format :json
          :format :json
          :keywords? true})
    (js/alert "标题及内容不能为空！")))

(defn- load-app-msg-for [aid]
  {:pre [aid]}
  (GET "/broadcast/app/msg"
       {:params {:aid aid}
        :handler (make-resp-handler
                  {:callback-success
                   (fn [{:keys [data]}]
                     (swap! pop-msg-info assoc :v-msg data))})
        :error-handler default-error-handler
        :response-format :json
        :format :json
        :keywords? true}))

(defn- delete-app-msg [{:keys [id aid]}]
  {:pre [id]}
  (DELETE "/broadcast/app/msg"
       {:params {:mid id :aid aid}
        :handler (make-resp-handler
                  {:callback-success
                   (fn [{:keys [data]}]
                     (load-app-msg-for aid)
                     (make-toast "删除成功！"))})
        :error-handler default-error-handler
        :response-format :json
        :format :json
        :keywords? true}))

(defn toggle-app-msg-dialog [{:keys [aid haier] :as user}]
  (swap! pop-msg-info (fn [old] (when-not (= (:aid old) aid)
                                  (load-app-msg-for aid)
                                  user)))
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

(defn- msg-info-dialog []
  (fn []
    (let [{:keys [aid haier] :as u} @pop-msg-info]
      [:div.alertViewBox {:style {:display :block :text-align :left}}
       [:div.app-msg.app-msg--dialog
        [:h4 (str "应用消息，目标用户：" haier)]
        [:div.app-msg__list
         (doall
          (for [{:keys [msg ts id status title]} (:v-msg @pop-msg-info)]
            [:div.app-msg__list-item {:key (str "m." id)}
             [:div.app-msg__list-item-date (utils/ts->readable-time ts)]
             [:div.app-msg__list-item-status status]
             [:div.app-msg__list-item-msg
              [:div.app-msg__list-item-msg--title (or title "")]
              [:div msg]]
             [:a.app-msg__list-item-delete {:href "javascript:;" :on-click #(delete-app-msg {:id id :aid aid})} "删除"]]))]
        [:div.app-msg__send-msg-head
         [:div "发送新消息"]]
        [:input {:type :text :value (or (:title @msg-store) "")
                 :on-change #(swap! msg-store assoc :title (-> % .-target .-value))}]
        [:textarea {:value (or (:msg @msg-store) )
                    :on-change #(swap! msg-store assoc :msg (-> % .-target .-value))}]
        (if-let [img (:img @msg-store)]
          [:img.mab__img {:src img :on-click #(swap! msg-store dissoc :img)}]
          [:input
           {:type :file
            :on-change (fn [e]
                         (let [file (first (array-seq (.. e -target -files)))]
                           (upload-static-file
                            {:file file
                             :callback-success
                             #(when-let [src (:data %)]
                                (swap! msg-store assoc :img src))})))}])
        [:div.mab__jpush-notify
         [:div.mab__jpush-notify-label "同时使用JPUSH推送？"]
         [:input {:type :checkbox :on-change #(swap! msg-store update :jpush? not)}]]
        [:div.app-msg__btn-group
         [:a.btn-light {:href "javascript:;" :on-click #(when-not (s/blank? @msg-store)
                                                          (send-app-msg-to u @msg-store))} "发送"]
         [:a.btn-light {:href "javascript:;" :on-click #(do
                                                          (toggle-app-msg-dialog u)
                                                          (reset! msg-store {}))} "关闭"]]]])))

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
             [:td
              [:p [:a {:href "javascript:;" :on-click #(toggle-user-info-dialog u)} "查看"]]
              [:p [:a {:href "javascript:;" :on-click #(toggle-app-msg-dialog u)} "消息"]]]]))]]
       (when @pop-user-info
         [user-info-dialog])
       (when @pop-msg-info
         [msg-info-dialog])])
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

(defn m-broadcast-panel []
  [:div {:class (if (= :m-broadcast @active-tab) "show" "hide")}
   [:div.mab
    [:label.um__label "消息广播"]
    [:div
     [:h4 "发送给所有用户"]
     [:div.mab__send
      [:div.mab__send-body
       [:input {:type :text :value (or (:title @msg-store) "")
                :on-change #(swap! msg-store assoc :title (-> % .-target .-value))}]
       [:textarea {:value (or (:msg @msg-store) )
                   :on-change #(swap! msg-store assoc :msg (-> % .-target .-value))}]
       (if-let [img (:img @msg-store)]
         [:img.mab__img {:src img :on-click #(swap! msg-store dissoc :img)}]
         [:input
          {:type :file
           :on-change (fn [e]
                        (let [file (first (array-seq (.. e -target -files)))]
                          (upload-static-file
                           {:file file
                            :callback-success
                            #(when-let [src (:data %)]
                               (swap! msg-store assoc :img src))})))}])
       [:div.mab__jpush-notify
        [:div.mab__jpush-notify-label "同时使用JPUSH推送？"]
        [:input {:type :checkbox :on-change #(swap! msg-store update :jpush? not)}]]
       [:div.mab__btn-group 
        [:a.btn-light {:href "javascript:;" :on-click #(when-not (s/blank? @msg-store)
                                                         (broadcast-app-msg @msg-store))} "发送"]
        [:a.btn-light {:href "javascript:;" :on-click #(show-tab :userlist)} "取消"]]]]]]])

(defn- search-user-jifen [params]
  (GET "/user"
       {:params params
        :handler (make-resp-handler
                  {:callback-success
                   (fn [{:keys [data]}]
                     (if data
                       (do
                         (swap! jifen-state assoc :user data)
                         (make-toast "查询成功！"))
                       (make-toast :error "未找到用户！")))})
        :error-handler (partial default-error-handler "/user")
        :response-format :json
        :keywords? true}))

(defn- load-jifen-code-list! []
  (GET "/jifen-code"
       {:handler (make-resp-handler
                  {:callback-success
                   (fn [{:keys [data]}]
                     (reset! jifen-code-list (or data [])))})
        :error-handler (partial default-error-handler "/jifen-code")
        :response-format :json
        :keywords? true}))

(defn- valid-jifen-code? [code]
  (re-seq #"^[A-Za-z0-9]{17}$" code))

(defn invalid-jifen-state? [{:keys [code score phone user]}]
  (or
   (when-not (valid-jifen-code? code)
     "兑换码格式不正确，应为17位字符")
   (when (not (:id user))
     "请输入有效的已注册用户手机号，并点击搜索")
   (when (or (s/blank? score) (<= (js/parseInt score) 0))
     "请输入有效的分值")))

(defn- add-jifen [{:keys [code score phone user byuser]}]
  (PUT "/jifen"
       {:params {:aid (:id user) :score score :code code :byuser byuser}
        :handler (make-resp-handler
                  {:callback-success
                   #(do
                      (load-jifen-code-list!)
                      (when byuser
                        (load-req-jifen-list))
                      (reset! active-jifen-panel :code-list)
                      (reset! jifen-state default-jifen-state)
                      (make-toast "操作成功！"))})
        :error-handler (partial default-error-handler "/jifen")
        :response-format :json
        :format :json
        :keywords? true}))

(defn- reject-coupon [{:keys [aid coupon]}]
  (POST "/jifen/coupon"
        {:params {:aid aid :coupon coupon :status "reject" :reason "兑换码无效或已使用"}
         :handler (make-resp-handler
                   {:callback-success
                    #(do
                       (load-req-jifen-list)
                       (make-toast "操作成功！"))})
         :error-handler (partial default-error-handler "/jifen/coupon")
         :response-format :json
         :format :json
         :keywords? true}))

(defn- add-jifen-panel []
  [:div {:class (if (= :add-jifen @active-jifen-panel) "show" "hide")}
   [:h4 "积分兑换"]
   [:div.umjf__body
    [:div "兑换码"]
    [:input {:type :text :value (:code @jifen-state)
             :on-change #(swap! jifen-state assoc :code (-> % .-target .-value))}]
    [:div "手机号"]
    [:div.umjf__phone-search
     [:input {:type :text :value (:phone @jifen-state)
              :placeholder "请输入用户的完整手机号"
              :on-change #(swap! jifen-state assoc :phone (-> % .-target .-value) :user nil)}]
     [:a.btn-light {:href "javascript:;" :on-click #(when-not (s/blank? (:phone @jifen-state))
                                                      (search-user-jifen @jifen-state))} "搜索"]]
    (when (:user @jifen-state)
      [:div ""])
    (when (:user @jifen-state)
      [:div (str "当前积分" (get-in @jifen-state [:user :score]))])
    [:div "分值"]
    [:input {:type :text :value (:score @jifen-state)
             :on-change #(swap! jifen-state assoc :score (-> % .-target .-value))}]
    [:div.umjf__btn-group
     [:a.btn-light {:href "javascript:;"
                    :class (if (invalid-jifen-state? @jifen-state) "hide" "show")
                    :on-click #(if-let [err (invalid-jifen-state? @jifen-state)]
                                 (js/alert err)
                                 (add-jifen @jifen-state))} "添加"]]]])

(defn- jifen-code-list-panel []
  [:div {:class (if (= :code-list @active-jifen-panel) "show" "hide")}
   [:div.umjf__code-list
    [:div.umjf__code-row.umjf__code-row--header
     [:div "兑换码"]
     [:div "用户手机号"]
     [:div "分值"]
     [:div "操作时间"]]
    (doall
     (for [{:keys [id code score ts haier aid]} @jifen-code-list]
       [:div.umjf__code-row {:key (str "cdl." id)}
        [:div code]
        [:div haier]
        [:div score]
        [:div (str (utils/ts->readable-time ts))]]))]])

(defn- coupon-row [{:keys [id coupon reason status score ts aid user] :as row}]
  (fn [_]
    [:div.umjf__coupon-row {:key (str "rcdl." id)}
     [:div coupon]
     [:div (str aid "/" (:haier user))]
     [:div score]
     [:div (ts-to-confirm-date ts)]
     [:div.umjf__review-ops-btn-group
      ;; [:a {:href "javascript:;"
      ;;      :on-click #(when (js/confirm (str "确认通过审核，添加" score "分？"))
      ;;                   (add-jifen {:code coupon :score score :user user :byuser true}))}
      ;;  "同意"]
      [:a {:href "javascript:;"
           :on-click #(when (js/confirm (str "确认拒绝？"))
                        (reject-coupon {:coupon coupon :aid aid}))}
       "拒绝"]]]))

(defn- user-request-jifen-panel []
  [:div {:class (if (= :user-request-jifen @active-jifen-panel) "show" "hide")}
   [:div.umjf__code-list
    [:div.umjf__coupon-row.umjf__coupon-row--header
     [:div "兑换码"]
     [:div "用户ID/手机号"]
     [:div "分值"]
     [:div "自动确认时间"]
     [:div "操作"]]
    (doall
     (for [req @request-jifen-code-list]
       ^{:key (str "cpr." (:coupon req))}
       [coupon-row req]))]])

(defn jifen-panel []
  [:div {:class (if (= :jifen-panel @active-tab) "show" "hide")}
   [:div.umjf
    [:h3.umjf__title "积分兑换"]
    [:div.umjf__return-btn
     [:a.btn-light {:href "javascript:;" :on-click #(do
                                                      (show-tab :userlist)
                                                      (reset! active-jifen-panel nil))}
      "< 返回"]]
    [:div.umjf__top-nav
     [:a {:href "javascript:;"
          :class (when (= :code-list @active-jifen-panel)
                   "umjf__top-nav-btn--active")
          :on-click #(do
                       (load-jifen-code-list!)
                       (reset! active-jifen-panel :code-list))}
      "查看已使用兑换码"]
     [:span "|"]
     [:a {:href "javascript:;"
          :class (when (= :add-jifen @active-jifen-panel)
                   "umjf__top-nav-btn--active")
          :on-click #(reset! active-jifen-panel :add-jifen)}
      "兑换积分"]
     [:span "|"]
     [:a {:href "javascript:;"
          :class (when (= :user-request-jifen @active-jifen-panel)
                   "umjf__top-nav-btn--active")
          :on-click #(do
                       (load-req-jifen-list)
                       (reset! active-jifen-panel :user-request-jifen))}
      "积分兑换申请"
      (let [n (count @request-jifen-code-list)]
        (when (pos? n)
          (str " (" (get-n-coupon-auto-confirming) "/" n ")")))]]
    [jifen-code-list-panel]
    [add-jifen-panel]
    [user-request-jifen-panel]]])


(defn- make-age-stats-string [age-dist]
  (reduce (fn [ss {:keys [age cnt percent]}]
            (str ss age "岁 : " cnt "人 (" (subs (str percent) 0 4) "%) " " \n"))
          ""
          (take 20 (reverse (sort-by :percent age-dist)))))

(defn- make-scale-stats-string [{:keys [health unknown kitchen]}]
  (str "未上报：" unknown "\n"
       (reduce-kv (fn [r k v]
                    (str r (name k) "：" v "\n"))
                  ""
                  health)
       (reduce-kv (fn [r k v]
                    (str r (name k) "：" v "\n"))
                  ""
                  kitchen)))

(defn- userstat-box [stat]
  [:div.um__head-stat
   [:div [:span [:a {:title (make-age-stats-string (-> stat :users :age-stats)) :href "javascript:;"} "年龄占比前20名" [:i.fa.fa-hand-paper-o]]]]
   [:div (str "总用户数：" (-> stat :users :all))]
   [:div
    [:span [:a {:title (make-scale-stats-string (-> stat :scale)) :href "javascript:;"} (str "设备用户数：" (-> stat :users :wm))
            [:i.fa.fa-hand-paper-o]]]]
   [:div (str "周活跃用户数：" (-> stat :week-cnt))]
   [:div (str "月活跃用户数：" (-> stat :month-cnt))]])

(defn um []
  (set-title! "用户管理")
  (when-not @userlist
    (get-userlist true))
  (when-not @companylist
    (get-companylist))
  (when (not @userstats)
    (get-userstats))
  (load-req-jifen-list)
  (fn []
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "管理"]
      "  >  "
      [:span.bkcrc-seceondT "用户管理"]]
     (let [stat (first @userstats)]
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
        [:div.um__head-opts--right.um__head-opts--stat
         (if (pos? (-> stat :users :all))
           [userstat-box stat]
           [:p "无用户"])]
        [:div.um__head-opts--left
         [:a.btn-light {:href "javascript:;" :on-click #(show-tab :user-add)} "添加用户"]
         [:a.btn-light {:href "javascript:;" :on-click #(show-tab :jifen-panel)} "积分兑换"
          (let [n (count @request-jifen-code-list)]
            (when (pos? n)
              (str " (" (get-n-coupon-auto-confirming) "/" n ")")))]]
        [:div.um__head-opts--left
         [:a.btn-light {:href "javascript:;" :on-click #(show-tab :m-broadcast)} "广播消息"]]])
     [userlist-table]
     [user-add-field]
     [m-broadcast-panel]
     [jifen-panel]
     [loading-spinner (:searching @search-state)]]))
