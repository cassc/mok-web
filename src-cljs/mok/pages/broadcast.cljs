(ns ^:figwheel-always mok.pages.broadcast
  "For rendering index/broadcast and messages pages"
  (:refer-clojure :exclude [partial atom flush])
  (:require
   [alandipert.storage-atom       :refer [local-storage]]
   [mok.states :refer [companylist me companyid->name appid->title]]
   [mok.utils :as utils :refer [make-toast pikaday-construct date-formatter
                      datetime-formatter loading-spinner make-spectrum-conf
                      format-date
                      default-error-handler admin? set-title! make-resp-handler]]
   [clojure.string :as s]
   [taoensso.timbre :as t]
   [reagent.core :as r :refer [partial atom]]
   [reagent.session :as session]
   [cljs-pikaday.reagent :as pikaday]
   [reagent.debug :as d :include-macros true]
   [secretary.core :as secretary :include-macros true]
   [goog.events :as events]
   [goog.history.EventType :as EventType]
   [ajax.core :refer [GET POST DELETE PUT]]
   [cljs-time.format :refer [unparse formatter parse show-formatters]]
   [cljs-time.coerce :refer [from-long]]
   [cljs-time.core :refer [date-time now time-zone-for-offset to-default-time-zone]])
  (:import [goog History]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; broacast states
(defonce bd-apps (atom nil))

(defonce default-bd-state {:cats-toggle false
                           :sex-toggle false
                           :age-toggle false
                           :platform-toggle false
                           :author ""
                           :companyid nil
                           :sex #{}
                           :age #{}
                           :platform #{}
                           :appid nil
                           :category nil})
(defonce bd-state (atom default-bd-state))

(def age-titles {"a" "小于18"
                 "b" "18~25"
                 "c" "26~30"
                 "d" "31~40"
                 "e" "41~50"
                 "f" "51~60"
                 "g" "60以上"})

(defonce preview-phone (local-storage (atom "") ::preview-phone))

(defonce queue-hour (local-storage (atom 20) ::queue-hour))
(defonce queue-minute (local-storage (atom 24) ::queue-minute))

(defonce pubdate (atom (js/Date.)))

;; draft message
(defonce msg-parts (atom []))

(defonce mid (atom 0))

(defonce color-atom (atom "6aa84f"))

;; sent messages load state
(defonce load-state (atom {:last nil :searching nil :page 1}))
;; msg queue load state
(defonce queue-load-state (atom {:last nil :searching nil :page 1}))

;; list of sent messages
(defonce msgs (atom nil))

;; list of queued messages
(defonce msg-queue-list (atom nil))

(defn catid->title [catid]
  (some #(when (= catid (:id %)) (:title %)) (session/get :bdcategories)))

(defonce show-float-dt-picker
  (atom false))


(defonce show-weixin-import (atom nil))

(defn reset-toggle
  "Hide all opened menu items"
  []
  (swap! bd-state dissoc :cats-toggle :sex-toggle :platform-toggle :age-toggle))

(defn- get-cats
  [appid]
  (GET (str "/public/bdcategories?appid=" appid)
       {:handler #(session/put! :bdcategories (:data %))
        :error-handler default-error-handler
        :response-format :json
        :keywords? true}))

(defn- get-bd-apps
  "Get pushable applications"
  []
  (GET
   "/applications?pushable=true"
   {:handler (make-resp-handler {:callback-success
                                 #(let [apps (:data %)
                                        haier-app (first apps)
                                        {:keys [appid companyid]} haier-app]
                                    (when-not haier-app
                                      (t/error "Illegal state, no app exists" apps))
                                    (reset! bd-apps apps)
                                    (get-cats appid)
                                    (swap! bd-state assoc :appid appid)
                                    (swap! bd-state assoc :companyid companyid))})
    :error-handler (partial default-error-handler "/applications")
    :response-format :json
    :keywords? true}))

(defn- get-message-list
  [& [replace?]]
  (swap! load-state assoc :searching true)
  (let [page (:page @load-state)
        success-fn (fn [{:keys [data]}]
                     (if replace?
                       (reset! msgs data)
                       (swap! msgs concat data))
                     (when (< (count data) 20)
                       (swap! load-state assoc :last true)))]
    (GET "/messages.json"
         {:params {:page page :with-preview (if (= page 1) "y" "n")}
          :handler (make-resp-handler {:callback-success success-fn})
          :error-handler default-error-handler
          :response-format :json
          :keywords? true
          :finally #(swap! load-state assoc :searching nil)})))


(defn get-mid []
  (swap! mid inc))

(defn title-node
  []
  {:id (get-mid) :type :title :placeholder "小标题" :content nil})

(defn paragraph-node
  ([]
   {:id (get-mid) :type :paragraph :placeholder "段落" :content nil})
  ([md]
   {:id (get-mid) :type :paragraph-md :placeholder "Markdown段落" :content nil}))

(defn image-node
  []
  {:id (get-mid) :type :image :src nil})




(defn- append-msg-part
  "Append a new msg-part after this node"
  ([part]
   (swap! msg-parts conj part))
  ([id part]
   (let [nid (:id part)
         helper (fn [parts]
                  (let [
                        [h [m & t]] (split-with #(not= id (:id %)) parts)]
                    (vec
                     (if m
                       (concat h [m part] t)
                       (concat h [part])))))]
     (t/debug "add" nid)
     (swap! msg-parts helper))))

(defn- delete-msg-part
  "Delete a msg-part node"
  [id]
  (letfn [(helper [parts]
            (let [[h [m & t]] (split-with #(not= id (:id %)) parts)]
              (vec
               (if m
                 (concat h t)
                 h))))]
    (t/debug "del" id)
    (swap! msg-parts helper)))


(defn- update-msg-part
  "Replace a node in msg-parts with the input node"
  [part]
  (letfn [(helper [parts]
            (let [[h [m & t]] (split-with #(not= (:id part) (:id %)) parts)]
              (vec
               (if m
                 (concat h [part] t)
                 h))))]
    (t/debug "update" (:id part))
    (swap! msg-parts helper)))


(defn- msg-part-editor-popup
  [id toggle]
  (fn []
    [:div.eq-edit {:style {:display (when @toggle :block)}}
     [:a.eq-edit-title {:href "javascript:;"
                        :on-click #(append-msg-part id (title-node))}
      "标题"]
     [:a.eq-edit-pictrue {:href "javascript:;"
                          :on-click #(append-msg-part id (image-node))}
      "图片"]
     [:a.eq-edit-text {:href "javascript:;"
                       :on-click #(append-msg-part id (paragraph-node))}
      "段落"]
     [:a.eq-edit-delete {:href "javascript:;"
                         :on-click #(delete-msg-part id)}
      "删除"]]))

(defn ptitle [node]
  (let [toggle (atom nil)
        {:keys [id placeholder content]} node
        ptitle-store (atom content)
        chn #(update-msg-part (assoc node :content (s/trim (or @ptitle-store ""))))]
    (fn []
      [:div.eq-block
       {:on-mouse-enter #(reset! toggle true)
        :on-mouse-leave #(reset! toggle false)}
       [:div.eq-block-smallTitle
        [:div.eq-block-circle]
        [:input.eq-block-smallTitle-text
         {:type "text"
          :value @ptitle-store
          :placeholder placeholder
          :on-change #(reset! ptitle-store (-> % .-target .-value))
          :on-blur chn}]]
       (when id
         [msg-part-editor-popup id toggle])])))

(defn- paragraph [node]
  (let [toggle (atom nil)
        {:keys [id placeholder content height md]} node
        content-store (atom content)
        ta-height (atom (or height "60px"))
        change-height (fn [e]
                        (reset! ta-height (str (min (-> e .-target .-scrollHeight) 300) "px")))
        chn (fn [e]
              ;;(update-msg-part (assoc node :content (-> e .-target .-innerHTML)))
              (update-msg-part (assoc node :content (s/trim (or @content-store "")) :height @ta-height :md md))
              (change-height e)
                               )]
    (fn []
      [:div.eq-block
       {:on-mouse-enter #(reset! toggle true)
        :on-mouse-leave #(reset! toggle false)}
       #_[:div.eq-block-contentText {:content-editable true
                                   :data-placeholder placeholder
                                   :on-blur chn
                                   :dangerouslySetInnerHTML
                                   {:__html content}}]

       [:textarea.eq-block-contentText {:style {:resize :none :height @ta-height}
                                        :title (if md "Markdown段落" "普通段落")
                                        :value @content-store
                                        :on-blur chn
                                        :on-change (fn [e]
                                                     (reset! content-store (-> e .-target .-value)))}]       
       (when id
         [msg-part-editor-popup id toggle])])))

(defn- upload-broadcast-resource
  "Upload an image.  

  Returns the path of the generated image. Upon success, the value for
  `storage-key` in `storage` will be updated to the returned value."
  [{:keys [image callback-success]}]
  (PUT (str "/broadcast/image/" (.getTime (js/Date.)))
       {:body (doto (js/FormData.)
                (.append "imageElement" image))
        :response-format :json
        :keywords? true
        :timeout 60000
        :handler (make-resp-handler
                  {:msg-success "保存成功！" :msg-fail "请求失败！"
                   :callback-success callback-success})
        :error-handler default-error-handler}))

(defn ppicture [node]
  (let [toggle (atom nil)
        {:keys [id src]} node
        img-src-atom (atom src)]
    (fn []
      [:div.eq-block
       {:on-mouse-enter #(reset! toggle true)
        :on-mouse-leave #(reset! toggle false)}
       [:div.eq-block-img
        [:img.contentImg {:src (or @img-src-atom "images/upload.jpg")}]
        [:label.fileUploadLabel
         {:style {:display (when @toggle :block)}}
         [:input.eq-block-file-btn
          {:type "file"
           :on-change (fn [e]
                        (let [file (first (array-seq (.. e -target -files)))]
                          (upload-broadcast-resource
                           {:image file
                            :callback-success
                            #(when-let [nsrc (:data %)]
                               (reset! img-src-atom nsrc)
                               (update-msg-part (assoc node :src nsrc)))})))}]
         "上传图片"]]
       (when id
         [msg-part-editor-popup id toggle])])))

(defn- to-int
  "Returns an integer in [min, max]. "
  [val min max]
  (let [nm (js/parseInt val)
        nm (cond
             (not (integer? nm)) min
             (< nm min) min
             (> nm max) max
             :else nm)]
    nm))



(defn- make-part
  []
  (fn [{:keys [id type content src] :as node}]
    (case type
      :title [ptitle node]
      :paragraph [paragraph node]
      :paragraph-md [paragraph (assoc node :md "md")]
      :image [ppicture node])))

(defn- pickday []
  [pikaday/date-selector
   {:date-atom pubdate
    :input-attrs {:style {:width "40px"}}
    :pikaday-attrs pikaday-construct}])

(defn- msg-composer
  "The editor part of the broadcast composer"
  []
  (let [cover-btn-shown (atom false)
        editable-txt (fn [{:keys [key placeholder]}]
                       {:type "text"
                        :value (key @bd-state)
                        :placeholder placeholder
                        :on-change (fn [e] (swap! bd-state assoc key (-> e .-target .-value)))
                        :on-blur (fn [e] (swap! bd-state assoc key (-> e .-target .-value s/trim)))})
        show-pickday (atom nil)]
    (fn []
      [:div.summernote-box
       [:center.edit-btns
        [:button.eq-smallTitle {:on-click #(append-msg-part (title-node))} "小标题"]
        [:button.eq-paragraph {:on-click #(append-msg-part (paragraph-node))} "段落"]
        [:button.eq-pictrue {:on-click #(append-msg-part (image-node))} "图片"]
        [:button.eq-paragraph {:on-click #(append-msg-part (paragraph-node "md"))} "MD段落"]]
       [:div.edit-common
        [:div.top]
        [:div.scene_title "发送消息模板"]
        [:div.eq-block-grid
         [:div#broadcastMessage.eq-block-grid-inner
          [:div.eq-block-wrap
           ;;[:input.eq-block-title (editable-txt {:key :title :placeholder "标题"}) (:title @bd-state)]
           [:input.eq-block-title
            {:type "text"
             :value (:title @bd-state)
             :placeholder "标题"
             :on-change (fn [e] (swap! bd-state assoc :title (-> e .-target .-value)))
             :on-blur (fn [e] (swap! bd-state assoc :title (-> e .-target .-value s/trim)))}]
           [:div.eq-block-subtitle
            ;;[:input.eq-block-subtitle-come (editable-txt {:key :author :placeholder "作者"}) (:author @bd-state)]
            [:input.eq-block-subtitle-come
             {:type "text"
              :value (:author @bd-state)
              :placeholder "作者"
              :on-change (fn [e] (swap! bd-state assoc :author (-> e .-target .-value)))
              :on-blur (fn [e] (swap! bd-state assoc :author (-> e .-target .-value s/trim)))}]
            [:span.eq-block-line "|"]
            (if @show-pickday
              [:div.eq-block-date {:on-blur #(reset! show-pickday nil)}
               [pickday]]
              [:div.eq-block-date {:on-click #(do (reset! show-pickday true) nil)}
               (format-date @pubdate)])]
           [:div {:style {:height "0" :overflow "hidden" :clear "both"}}]
           [:div.eq-block-cover
            {:on-mouse-enter #(do (reset! cover-btn-shown true) nil)
             :on-mouse-leave #(do (reset! cover-btn-shown false) nil)}
            [:img.coverImg {:src (or (:coverImage @bd-state) "images/upCover.jpg") :width "100%"}]
            [:label.fileUploadLabel
             {:style {:display (when @cover-btn-shown :block)}}
             [:input.eq-block-file-btn
              {:type "file"
               :on-change (fn [e]
                            (let [file (first (array-seq (.. e -target -files)))]
                              (upload-broadcast-resource
                               {:image file
                                :callback-success #(when-let [nsrc (:data %)]
                                                     (swap! bd-state assoc :coverImage nsrc))})))}]
             "上传封面"]]
           ;;[:input.eq-block-abstract (editable-txt {:key :abstract :placeholder "摘要"}) (:abstract @bd-state)]
           [:input.eq-block-abstract
            {:type "text"
             :value (:abstract @bd-state)
             :placeholder "摘要"
             :on-change (fn [e] (swap! bd-state assoc :abstract (-> e .-target .-value)))
             :on-blur (fn [e] (swap! bd-state assoc :abstract (-> e .-target .-value s/trim)))}]
           (when (pos? (count @msg-parts))
             (t/debug "rendering msg parts")
             (doall
              (for [node @msg-parts]
                ^{:key {:id node}} [make-part node ])))]]]
        [:div.botton]]])))

(defn- cats-list
  []
  [:div.bdcategories.sexPos {:style {:display :block}}
   (doall
    (for [c (session/get :bdcategories)
          :let [{:keys [title_en title id bgcolor]} c]]
      ^{:key id}
      [:span
       {:on-click #(swap! bd-state assoc :category id)}
       [:b title]
       [:i.bkcrc-sureImg
        {:class (when (= (:category @bd-state) id) "toggleClass-img")}]
       [:div {:style {:width "18px" :height "18px" :position :absolute :left "26px" :top "11px" :background-color (str "#" bgcolor)}}]]))])

(defn- sex-list
  []
  (let [display (if (:sex-toggle @bd-state) :block :none)]
    [:div.bdsex.sexPos
     {:style {:display display}}
     [:span
      {:on-click #(swap! bd-state update-in [:sex] (if ((:sex @bd-state) 1) disj conj) 1)}
      [:b "男"] [:i.bkcrc-sureImg
                 {:class (when ((:sex @bd-state) 1) "toggleClass-img")}]]
     [:span
      {:on-click #(swap! bd-state update-in [:sex] (if ((:sex @bd-state) 2) disj conj) 2)}
      [:b "女"] [:i.bkcrc-sureImg
                 {:class (when ((:sex @bd-state) 2) "toggleClass-img")}]]]))

(defn- age-span [key]
  (fn [key]
    [:span
     {:on-click #(swap! bd-state update-in [:age] (if ((:age @bd-state) key) disj conj) key)}
     [:b (age-titles key)]
     [:i.bkcrc-sureImg
      {:class (when ((:age @bd-state) key) "toggleClass-img")}]]))

(defn- age-list
  []
  [:div.bdsex.sexPos
   {:style {:display (if (:age-toggle @bd-state) :block :none)}}
   [age-span "a"]
   [age-span "b"]
   [age-span "c"]
   [age-span "d"]
   [age-span "e"]
   [age-span "f"]
   [age-span "g"]])

(defn- platform-span [key]
  [:span
   {:on-click #(swap! bd-state update-in [:platform] (if ((:platform @bd-state) key) disj conj) key)}
   [:b key]
   [:i.bkcrc-sureImg
    {:class (when ((:platform @bd-state) key) "toggleClass-img")}]])

(defn- platform-list
  []
  [:div.bdsex.sexPos
   {:style {:display (if (:platform-toggle @bd-state) :block :none)}}
   [platform-span "ios"]
   [platform-span "android"]])

(defn- swap-state
  "Swap toggle state and stop event propagation"
  [k e]
  (swap! bd-state update-in [k] not)
  (.stopPropagation e)
  (.preventDefault e))

(defonce show-preview-phone-dialog
  (atom false))


(defonce loading? (atom false))

(defn- reset-bd-state!
  "Reset states in broadcast page"
  []
  (reset! bd-state default-bd-state)
  (reset! pubdate (js/Date.))
  (reset! msg-parts [])
  (reset! show-preview-phone-dialog false)
  (reset! show-float-dt-picker nil))

(defn- validate-send-broadcast
  "Returns truth if validation fails"
  [{:keys [appid category sex companyid phone preview title abstract coverImage post] :as params}]
  (t/debug "validate-send-broadcast" params)
  (letfn [(toast [msg] (make-toast :error msg) true)]
    (or
     (when (and preview (not (re-seq #"^\d{11}$" (str phone))))
       (toast "请输入正确的手机号！"))
     (when-not appid
       (toast "请选择应用！"))
     (when-not companyid
       (toast "请选择消息厂商！"))
     (when-not category
       (toast "请选择消息类别！"))
     (when-not (seq sex)
       (toast "请选择消息性别！"))
     (when-not post
       (or
        (when (s/blank? title)
          (toast "请输入标题！"))
        (when (s/blank? abstract)
          (toast "请填写摘要！"))
        (when (s/blank? coverImage)
          (toast "请上传图片封面！")))))))

(defn- handle-bd-response
  "Handle `resp` of the sent `msg`"
  [bd {:keys [code msg fails data] :as resp}]
  (t/info bd)
  (t/debug resp)
  (case code
    0 (do
        (make-toast "发送成功！")
        (reset! show-preview-phone-dialog false)
        (reset! show-float-dt-picker false)
        (when (:preview bd)
          (t/info "opening" (str "/message/" (:uri data)))
          (utils/open! (str "/message/" (:uri data))))) 
    2 (do (make-toast :error "Session已过期，请刷新页面并重新登录！"))
    (make-toast :error (or (get-in fails [0 :body :error :message]) msg) "发送失败！"))
  (when-not (:preview bd)
    (if (:queue-time bd)
      (reset! msg-queue-list nil)
      (reset! msgs nil))))

(defn- send-broadcast
  "Send broadcast. "
  [& [{:keys [preview queue-time]}]]
  (when-not @loading? ;; do nothing if sending in progress
    (reset! loading? true)
    (let [{:keys [sex platform age] :as msg} @bd-state
          ;; server requires sex to be a number
          sex (if (next sex) 0 (first sex))
          age (seq age)
          platform (if (#{2 0} (count platform))
                     "all"
                     (first platform))
          params (-> msg
                     (select-keys [:title :author :coverImage :abstract :category :companyid :appid])
                     (assoc :pubdate (unparse date-formatter (to-default-time-zone @pubdate))))
          params (assoc params
                        :preview preview
                        :queue-time queue-time
                        :sex sex
                        :age age
                        :platform platform)]
      (if (validate-send-broadcast (assoc @bd-state :preview preview :phone @preview-phone))
        (reset! loading? false)
        (POST "/broadcast"
              {:params (assoc params :parts @msg-parts :phone @preview-phone)
               :format :json
               :response-format :json
               :keywords? true
               :timeout 60000
               :handler (partial handle-bd-response params)
               :error-handler default-error-handler
               :finally #(reset! loading? false)})))))


(defn- float-date-picker
  []
  [:div.alertViewBox {:style {:display :block}}
   [:div.alertViewBoxContent
    [:ul
     [:li.companyEditShdow-header.pt10 "请输入发送时间"]
     [:li 
      [:span {:style {:float :left}}
       [:span.floatDatePicker [pickday]]
       [:span
        [:input.floatTimePicker {:value @queue-hour :type :number
                                 :on-change #(reset! queue-hour (to-int (-> % .-target .-value) 0 23))}]
        ":"
        [:input.floatTimePicker {:value @queue-minute :type :number
                                 :on-change #(reset! queue-minute (to-int (-> % .-target .-value) 0 59))}]]]]
     [:li {:style {:border "none" :margin-top "15px"}}
      [:div.companyEditShdow-i18-buttons
       [:button.alertViewBox-compilerDelete
        {:type "button"
         :on-click #(reset! show-float-dt-picker nil)} "取消"]
       "    "
       [:input#pNewSubmit.companyEditShdow-submit.companyEditShdow-preview
        {:type "button"
         :style {:height "32px"}
         :on-click #(send-broadcast {:queue-time (str (format-date @pubdate) " " @queue-hour ":" @queue-minute)})
         :value "确认"}]]]]]])

(defn- preview-phone-dialog
  []
  (fn []
    [:div.alertViewBox {:style {:display :block}}
     [:div.alertViewBoxContent
      [:ul
       [:li.companyEditShdow-header.pt10 "请输入手机号码" [:span {:style {:font-size "xx-small"}} "预览不区分开发与正式环境"]]
       [:li {:style {:height "50px"}}
        [:span.companyEditShdow-leftBox.alertViewBoxSpan "手机号码："]
        [:input.previewPhone.ViewBoxContentInput
         {:style {:width "120px"
                  :!important true}
          :disabled (not (admin?))
          :on-change #(reset! preview-phone (-> % .-target .-value))
          :value (or @preview-phone "")}]]
       [:li {:style {:border "none" :margin-top "15px"}}
        [:div.companyEditShdow-i18-buttons
         [:button.alertViewBox-compilerDelete
          {:type "button"
           :on-click #(reset! show-preview-phone-dialog false)} "取消"]
         "    "
         [:input#pNewSubmit.companyEditShdow-submit.companyEditShdow-preview
          {:type "button"
           :style {:height "32px"}
           :on-click (fn [e]
                       (send-broadcast {:preview true})
                       nil)
           :value "确认"}]]]]]]))


(defn target-span []
  (fn []
    (let [{:keys [appid category companyid sex age platform]} @bd-state]
      [:span.bkcrc-seceondT
       (str "发送消息至 "
            (when appid
              (str (appid->title appid) " ["
                   (when companyid (companyid->name companyid))
                   "] "
                   (when (sex 1) "男")
                   (when (sex 2) "女")
                   " "
                   (s/join ", " (map age-titles age))
                   " "
                   (s/join ", " platform)
                   " "
                   (when category (catid->title category)))))])))

(defn broadcast []
  (set-title!  "发送消息")
  (when-not @bd-apps
    (get-bd-apps))
  (fn []
    [:div {:on-click reset-toggle} ;; reset toggles
     (if (seq @bd-apps)
       [:div.bkcr-content
        [:p.bkcrc-title
         [:span "APP推送消息"]
         "  >  "
         [target-span]]
        [:ul.bkcrc-tab
         {:on-click #(do
                       (.stopPropagation %)
                       (.preventDefault %))}
         [:li
          [:a {:href "#"
               :on-click (partial swap-state :sex-toggle)}
           "性别"]
          [sex-list]]
         [:li
          [:a {:href "#"
               :on-click (partial swap-state :age-toggle)}
           "年龄"]
          [age-list]]
         [:li
          [:a {:href "#"
               :on-click (partial swap-state :platform-toggle)}
           "设备"]
          [platform-list]]
         [:li
          [:a {:href "#"
               :on-click (fn [e]
                           (when (:appid @bd-state)
                             (swap-state :cats-toggle e)))}
           "消息分类"]
          (when (:cats-toggle @bd-state)
            [cats-list])]
         
         [:div {:style {:height "0" :overflow "hidden" :clear "both"}}]]
        [msg-composer]
        [:div.back-edit-button
         [:input.sendMessage.fa.fa-paper-plane
          {:type "button"
           :value "\uf1d8 立即发送"
           :on-click #(send-broadcast)}]
         [:input.sendMessage.fa
          {:type "button"
           :value "\uf017 定时发送"
           :on-click #(do
                        (reset! show-float-dt-picker true)
                        nil)}]
         [:input.sendMessage.fa
          {:type "button"
           :value "\uf06e 预览"
           :on-click #(do (reset! show-preview-phone-dialog true) nil)}]
         [:input.sendMessage.fa
          {:type "button"
           :value "\uf011 Reset"
           :on-click #(do (reset-bd-state!) nil)}]]
        (when @show-preview-phone-dialog
          [preview-phone-dialog])
        (when @show-float-dt-picker
          [float-date-picker])
        [loading-spinner @loading?]])]))


(defn- load-more [{:keys [page-atom load-func]}]
  #(let [wh (.height (js/$ js/document))
         pos (.scrollTop (js/$ js/document))
         h (.height (js/$ js/window))]
     (when-not (or (< (+ h pos 10) wh)  (:last @page-atom) (:searching @page-atom))
       (swap! page-atom update-in [:page] inc)
       (load-func))))

(defn- delete-message
  [id title]
  (when (js/confirm (str "确认删除" title "？"))
    (DELETE (str "/message/" id)
            {:handler (make-resp-handler {:callback-success (fn [_] (swap! msgs (fn [mlist] (remove #(= id (:id %)) mlist))))})
             :error-handler default-error-handler
             :response-format :json
             :keywords? true})))

(defn- delete-preview-message
  [uri]
  (let [folder (subs uri 0)]
    (DELETE "/preview-message" 
            {:params {:uri uri}
             :format :json
             :handler (make-resp-handler {:callback-success (fn [_] (swap! msgs (fn [mlist] (remove #(= uri (:uri %)) mlist))))})
             :error-handler default-error-handler
             :response-format :json
             :keywords? true})))

(defn- delete-msq-queue
  [id title]
  (when (js/confirm (str "确认取消发送" title "？"))
    (DELETE (str "/msg-queue/" id)
            {:handler (make-resp-handler {:callback-success (fn [_] (swap! msg-queue-list (fn [mlist] (remove #(= id (:parts_id %)) mlist))))})
             :error-handler default-error-handler
             :response-format :json
             :keywords? true})))


(def msg-list
  (let [fetch-func (load-more {:page-atom load-state
                               :load-func get-message-list})]
    (with-meta
    (fn []
      [:table.news-table {:cell-padding "0" :cell-spacing "0"}
       [:tbody
        (doall
         (for [m @msgs
               :let [{:keys [id title abstract cover status uri companyid sex ts appid pv buid]} m
                     preview? (zero? id)
                     phone (when preview? (subs uri 0 11))]]
           [:tr {:key (str "msg-list" buid)}
            [:td {:width "205"}
             [:a {:target "_blank" :href (str "/message/" uri)}
              [:img {:src (str "/message/" cover) :width "164px" :height "108px"}]]]
            [:td {:width "400"}
             [:p.news-table-p1 (str "ID：" id )]
             [:p.news-table-p1 title]
             [:p.news-table-p2 abstract]]
            [:td {:width "275"}
             [:p.news-table-p1 (str "状态: " (if preview? "消息预览" status))]
             [:p.news-table-p1 (str "App: " (appid->title appid))]
             [:p.news-table-p1 (str "目标: " (or phone (str (companyid->name companyid) " (" companyid ")")))]
             [:p.news-table-p1 (str "PV: " pv)]
             [:p.news-table-p2 (unparse datetime-formatter (to-default-time-zone (from-long ts)))]]
            [:td
             (if (admin?)
               [:button.destroy.fa {:on-click #(if (pos? id)
                                                 (delete-message id title)
                                                 (delete-preview-message uri))} "\uf00d"]
               "")]]))]])
    {:component-will-mount #(.addEventListener js/window "scroll" fetch-func)
     :component-will-unmount #(.removeEventListener js/window "scroll" fetch-func)})))

(defn messages
  []
  (set-title!  "历史消息")
  (fn []
    (when-not @msgs
      (get-message-list))
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "APP推送消息"]
      "  >  "
      [:span.bkcrc-seceondT
       "消息管理 "
       [:a {:href "javascript:;"
            :title "点击刷新列表"
            :on-click #(do
                         (swap! load-state assoc :page 1 :last nil)
                         (get-message-list true))}
        [:i.fa.fa-lg.fa-refresh]]]]
     [msg-list]]))


(defn get-msg-queue-list
  [& [replace?]]
  (letfn [(sfunc [{:keys [data]}]
            (if replace?
              (reset! msg-queue-list data)
              (swap! msg-queue-list concat data))
            (when (< (count data) 20)
              (swap! queue-load-state assoc :last true)))]
    (GET "/msg-queue.json"
         {:params (select-keys @queue-load-state [:page])
          :handler (make-resp-handler {:callback-success sfunc})
          :error-handler default-error-handler
          :response-format :json
          :keywords? true
          :finally #(swap! queue-load-state assoc :searching nil)})))


(def display-msg-queue
  (let [fetch-func (load-more {:page-atom queue-load-state
                               :load-func get-msg-queue-list})]
    (with-meta
      (fn []
        [:table.news-table {:cell-padding "0" :cell-spacing "0"}
         [:tbody
          (doall
           (for [m @msg-queue-list
                 :let [{:keys [parts queue_ts draft_ts parts_id]} m
                       {:keys [title abstract coverImage status uri companyid sex]} parts]]
             ^{:key (str "display-msg-queue" parts_id)}
             [:tr
              [:td {:width "205"}
               [:a {:target "_blank" :href (str "/preview/msg-queue/" parts_id) :title "新窗口中预览"}
                [:img {:src (str coverImage) :width "164px" :height "108px"}]]]
              [:td {:width "400"}
               [:p.news-table-p1 (str "ID：" parts_id)]
               [:p.news-table-p1 title]
               [:p.news-table-p2 abstract]]
              [:td {:width "255"}
               [:p.news-table-p1 
                (str "目标公司："  (companyid->name companyid))]
               [:p.news-table-p2 [:b (str "预计发送时间：" (unparse datetime-formatter (to-default-time-zone (from-long queue_ts))))]]
               [:p.news-table-p2 (str "草稿生成时间：" (unparse datetime-formatter (to-default-time-zone (from-long draft_ts))))]]
              [:td
               (if (admin?)
                 [:button.destroy.fa {:title "删除"
                                      :on-click #(delete-msq-queue parts_id title)} "\uf00d"]
                 "")]]))]])
      {:component-will-mount #(.addEventListener js/window "scroll" fetch-func)
       :component-will-unmount #(.removeEventListener js/window "scroll" fetch-func)})))





(defn msg-queue
  []
  (set-title! "消息队列")
  (fn []
    (when-not @msg-queue-list
      (get-msg-queue-list))
    [:div.id.bkcr-content
     [:p.bkcrc-title
      [:span "APP推送消息"]
      "  >  "
      [:span.bkcrc-seceondT
       "消息队列 "
       [:span [:a {:href "javascript:;"
                   :title "点击刷新列表"
                   :on-click #(do
                                (swap! queue-load-state assoc :page 1 :last nil)
                                (get-msg-queue-list true))}
               [:i.fa.fa-lg.fa-refresh]]]]]
     [display-msg-queue]]))
