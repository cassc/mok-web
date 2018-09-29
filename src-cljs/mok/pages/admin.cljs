(ns mok.pages.admin
  "Admin page"
  (:refer-clojure :exclude [partial atom flush])
  (:require
   [mok.states :refer [companylist]]
   [mok.utils :as utils :refer [default-error-handler set-title! rightcode-bit-map]]
   [mok.pages.company :refer [get-companylist]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [goog.events :as events]
   [goog.history.EventType :as EventType]
   [ajax.core :refer [GET DELETE PUT POST]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]])
  (:import [goog History]))

(defonce sec-display (atom nil))

(defn set-sec-display [ky]
  (reset! sec-display ky))

(defn edit-password [params]
  (POST "/admin/editPassword"
        {:params params
         :format :json
         :response-format :json
         :keywords? true
         :timeout 60000
         :handler (fn [{:keys [code msg]}]
                    (if (zero? code)
                      (do
                        (js/alert "修改成功，请重新登录")
                        (utils/redirect! "/logout"))
                      (js/alert msg)))
         :error-handler default-error-handler}))

(defn cancel-buttn
  ([]
   (cancel-buttn nil))
  ([func]
   [:a.btn-light
    {:href "javascript:;"
     :on-click #(do
                  (set-sec-display nil)
                  (when func
                    (func)))}
    "取消"]))

(defn- validate-password-edit
  "Returns nil if no error, otherwise returns err message"
  [{:keys [password old-password repeat-password]}]
  (or
   (when (some s/blank? [password old-password repeat-password])
     "密码不能为空")
   (when (= password old-password)
     "新旧密码不能相同")
   (when (not= password repeat-password)
     "新密码两次输入不一致")))

(defn password-edit-field []
  (let [default-fields {:password ""
                        :repeat-password ""
                        :old-password ""}
        password-store (atom default-fields)]
    (fn []
      [:div.admin__part
       [:div.admin__part-title "修改密码"]
       [:a.btn-light.admin__top-btn
        {:class (if (= @sec-display :edit-password) "hide" "show")
         :href "javascript:;" :on-click #(set-sec-display :edit-password)} "点击修改"]
       
       [:div.admin__field {:class (if (= @sec-display :edit-password) "show" "hide")}
        [:div.admin__field-row
         [:label "原密码"]
         [:input {:type :password
                  :value (:old-password @password-store)
                  :on-change #(swap! password-store assoc :old-password (-> % .-target .-value))}]]
        [:div.admin__field-row
         [:label "新密码"]
         [:input {:type :password
                  :value (:password @password-store)
                  :on-change #(swap! password-store assoc :password (-> % .-target .-value))}]]
        [:div.admin__field-row
         [:label "请再次输入新密码"]
         [:input {:type :password
                  :value (:repeat-password @password-store)
                  :on-change #(swap! password-store assoc :repeat-password (-> % .-target .-value))}]]
        [:div.admin__field-buttons
         [:a.btn-light {:href "javascript:;"
                        :on-click #(if-let [err (validate-password-edit @password-store)]
                                     (js/alert err)
                                     (edit-password @password-store))}
          "确认"]
         [cancel-buttn #(reset! password-store default-fields)]]]])))

(defonce default-admin {:email "" :password "" :rightcode 0})
(defonce admin-store (atom default-admin))
(defonce admin-list-store (atom nil))

(defn- load-admin-list []
  (GET "/admin"
       {:response-format :json
        :keywords? true
        :timeout 60000
        :handler (fn [{:keys [code data]}]
                   (when (zero? code)
                     (reset! admin-list-store data)))
        :error-handler default-error-handler}))

(defn- validate-admin-create [{:keys [email password rightcode]}]
  (or
   (when (or (s/blank? email) (not (s/includes? email "@")))
     "邮箱无效")
   (when (s/blank? password)
     "密码不能为空")
   (when-not (pos? rightcode)
     "请选择至少一个权限")))

(defn- create-admin [params]
  (PUT "/admin"
        {:params params
         :format :json
         :response-format :json
         :keywords? true
         :timeout 60000
         :handler (fn [{:keys [code msg]}]
                    (if (zero? code)
                      (do
                        (reset! admin-store default-admin)
                        (set-sec-display :show-admin)
                        (load-admin-list)
                        (js/alert "创建成功！")
                        )
                      (js/alert msg)))
         :error-handler default-error-handler}))


(defn rightcode-to-string
  ([rightcode]
   (str
    (when (= rightcode 65535)
      "管理员. ")
    (->> (keys rightcode-bit-map)
         (map (partial rightcode-to-string rightcode))
         (filter identity)
         (s/join ". "))))
  ([rightcode n]
   (when (bit-test rightcode n)
     (get-in rightcode-bit-map [n :title]))))

(defn admin-create-field []
  (fn []
    [:div.admin__part
     [:div.admin__part-title "创建管理员"]
     [:a.btn-light.admin__top-btn
      {:class (if (= :create-admin @sec-display) "hide" "show")
       :href "javascript:;" :on-click #(set-sec-display :create-admin)} "点击创建"]
     [:div.admin__field {:class (if (= :create-admin @sec-display) "show" "hide")}
      [:div.admin__field-row
       [:label [:span "邮箱"]]
       [:input.admin__field-long-input {:type :email
                                        :value (:email @admin-store)
                                        :on-change #(swap! admin-store assoc :email (-> % .-target .-value))}]]
      [:div.admin__field-row
       [:label "请输入新密码"]
       [:input.admin__field-long-input
        {:type :password
         :value (:password @admin-store)
         :on-change #(swap! admin-store assoc :password (-> % .-target .-value))}]]
      [:div.admin__field-row
       [:div "权限"]
       [:div.admin__right-options
        [:div
         [:label {:for "adm-admin"} "管理员"]
         [:input#adm-admin {:type :checkbox
                            :checked (= (:rightcode @admin-store) 65535)
                            :on-change #(let [tpe (-> % .-target .-checked)]
                                          (if tpe
                                            (swap! admin-store assoc :rightcode 65535)
                                            (swap! admin-store assoc :rightcode 0)))}]]
        [:div
         [:label {:for "adm-user"} "用户管理"]
         [:input#adm-user {:type :checkbox
                           :checked (bit-test (:rightcode @admin-store) 4)
                           :on-change #(let [tpe (-> % .-target .-checked)]
                                         (if tpe
                                           (swap! admin-store update-in [:rightcode] bit-set 4)
                                           (swap! admin-store update-in [:rightcode] bit-clear 4)))}]]
        [:div
         [:label {:for "adm-push"} "推送消息"]
         [:input#adm-push {:type :checkbox
                           :checked (bit-test (:rightcode @admin-store) 3)
                           :on-change #(let [tpe (-> % .-target .-checked)]
                                         (if tpe
                                           (swap! admin-store update-in [:rightcode] bit-set 3)
                                           (swap! admin-store update-in [:rightcode] bit-clear 3)))}]]
        [:div
         [:label {:for "adm-fd"} "用户反馈"]
         [:input#adm-fd {:type :checkbox
                         :checked (bit-test (:rightcode @admin-store) 2)
                         :on-change #(let [tpe (-> % .-target .-checked)]
                                       (if tpe
                                         (swap! admin-store update-in [:rightcode] bit-set 2)
                                         (swap! admin-store update-in [:rightcode] bit-clear 2)))}]]
        [:div
         [:label {:for "adm-mblog"} "社区活动"]
         [:input#adm-mblog {:type :checkbox
                            :checked (bit-test (:rightcode @admin-store) 6)
                            :on-change #(let [tpe (-> % .-target .-checked)]
                                          (if tpe
                                            (swap! admin-store update-in [:rightcode] bit-set 6)
                                            (swap! admin-store update-in [:rightcode] bit-clear 6)))}]]]]
      [:div.admin__field-buttons
       [:a.btn-light {:href "javascript:;"
                      :on-click #(if-let [err (validate-admin-create @admin-store)]
                                   (js/alert err)
                                   (create-admin @admin-store))}
        "确认"]
       [cancel-buttn #(reset! admin-store default-admin)]]]]))

(defn- delete-admin-by-id [id]
  (DELETE "/admin"
        {:params {:id id}
         :format :json
         :response-format :json
         :keywords? true
         :timeout 60000
         :handler (fn [{:keys [code msg]}]
                    (if (zero? code)
                      (load-admin-list)
                      (js/alert msg)))
         :error-handler default-error-handler}))


(defn- update-admin [{:keys [id rightcode]}]
  (POST "/admin/rightcode"
          {:params {:id id :rightcode rightcode}
           :format :json
           :response-format :json
           :keywords? true
           :timeout 60000
           :handler (fn [{:keys [code msg]}]
                      (if (zero? code)
                        (do
                          (load-admin-list)
                          (js/alert "修改成功！"))
                        (js/alert msg)))
           :error-handler default-error-handler}))

(defn admin-item [{:keys [username phone email rightcode id] :as admin}]
  (let [uid (or username phone email)
        admin-store (atom admin)]
    (fn [{:keys [username phone email rightcode id]}]
      [:div.admin__item
       [:div.admin__item-left
        [:div.admin__item-uid uid]
        [:div.admin__item-rightcode
         [:div.admin__right-options
          [:div
           [:label {:for "adm-admin"} "管理员"]
           [:input#adm-admin {:type :checkbox
                              :checked (= (:rightcode @admin-store) 65535)
                              :on-change #(let [tpe (-> % .-target .-checked)]
                                            (if tpe
                                              (swap! admin-store assoc :rightcode 65535)
                                              (swap! admin-store assoc :rightcode 0)))}]]
          [:div
           [:label {:for "adm-user"} "用户管理"]
           [:input#adm-user {:type :checkbox
                             :checked (bit-test (:rightcode @admin-store) 4)
                             :on-change #(let [tpe (-> % .-target .-checked)]
                                           (if tpe
                                             (swap! admin-store update-in [:rightcode] bit-set 4)
                                             (swap! admin-store update-in [:rightcode] bit-clear 4)))}]]
          [:div
           [:label {:for "adm-push"} "推送消息"]
           [:input#adm-push {:type :checkbox
                             :checked (bit-test (:rightcode @admin-store) 3)
                             :on-change #(let [tpe (-> % .-target .-checked)]
                                           (if tpe
                                             (swap! admin-store update-in [:rightcode] bit-set 3)
                                             (swap! admin-store update-in [:rightcode] bit-clear 3)))}]]
          [:div
           [:label {:for "adm-fd"} "用户反馈"]
           [:input#adm-fd {:type :checkbox
                           :checked (bit-test (:rightcode @admin-store) 2)
                           :on-change #(let [tpe (-> % .-target .-checked)]
                                         (if tpe
                                           (swap! admin-store update-in [:rightcode] bit-set 2)
                                           (swap! admin-store update-in [:rightcode] bit-clear 2)))}]]
          [:div
           [:label {:for "adm-mblog"} "社区活动"]
           [:input#adm-mblog {:type :checkbox
                              :checked (bit-test (:rightcode @admin-store) 6)
                              :on-change #(let [tpe (-> % .-target .-checked)]
                                            (if tpe
                                              (swap! admin-store update-in [:rightcode] bit-set 6)
                                              (swap! admin-store update-in [:rightcode] bit-clear 6)))}]]]]]
       (when-not (= username "admin")
         [:div.admin__item-right
          [:a.btn-light.admin__item-save-btn
           {:href "javascript:;"
            :class (if (or (= admin @admin-store)
                           (not (pos? (:rightcode @admin-store))))
                     "hide"
                     "show")
            :on-click #(update-admin @admin-store)}
           "保存"]
          [:a.btn-light
           {:href "javascript:;"
            :on-click #(when (js/confirm (str "确认删除管理员：" uid "？"))
                         (delete-admin-by-id id))}
           "删除"]])])))


(defn admin-list-field []
  [:div.admin__part
   [:div.admin__part-title "管理员列表"]
   [:a.btn-light.admin__top-btn
    {:class (if (= :show-admin @sec-display) "hide" "show")
     :href "javascript:;"
     :on-click (fn []
                 (set-sec-display :show-admin)
                 (load-admin-list))}
    "点击查看"]
   [:div.admin__field {:class (if (= :show-admin @sec-display) "show" "hide")}
    [:div
     (doall
      (for [{:keys [username phone email rightcode id] :as admin} @admin-list-store
            :let [uid (or username phone email)]]
        ^{:key (str "admin.list." username phone email rightcode)}
        [admin-item admin]))]
    [:div.admin__field-buttons
     [:a.btn-light {:href "javascript:;"
                    :on-click (fn []
                                (set-sec-display nil))}
      "隐藏"]]]])

(defn admin-page []
  (set-title! "管理员设置")
  (fn []
    [:div.admin__container
     [password-edit-field]
     [admin-create-field]
     [admin-list-field]]))
