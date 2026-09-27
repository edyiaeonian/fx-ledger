# fx-ledger 實作計畫

- 日期:2026-09-25
- 依據:[設計文件](../specs/2026-09-25-fx-ledger-design.md)
- 狀態:階段 0–7 完成(2026-09-27);與設計的差異記錄於設計文件第 11 節

## 進行方式

- **邊做邊學 Java**:每個階段開頭列出會用到的新 Java 概念,先以 Python 對照說明,確認看懂再寫
- **測試先行**:每個功能先寫會失敗的測試,再寫讓它通過的程式碼
- **每個階段結束時**:全部測試通過、CI 綠燈、commit 一次;階段之間是自然的暫停點
- **不跨階段趕進度**:一個階段沒看懂,就不進下一個

以每週 5–15 小時估算,整體約 8–14 週。估計只是參考,沒有期限。

## 新增的實作決定(設計文件未涵蓋)

| 決定 | 理由 |
|---|---|
| 資料存取用 Spring 的 `JdbcClient` 寫明確的 SQL,不用 JPA/Hibernate | `SELECT ... FOR UPDATE`、上鎖順序、餘額更新都必須精確控制;寫出來的 SQL 就是實際執行的 SQL,沒有 ORM 在背後產生查詢。也順便練 SQL |
| Docker 使用 Docker Desktop | 個人使用免費;Testcontainers 不需額外設定即可運作 |
| 主要識別碼用 UUID | 對外 API 不暴露流水號(無法猜出其他顧客的資料量);可在應用程式端先產生 |

## 階段 0:環境與骨架

**目標**:在 Mac 上跑起一個最小的 Spring Boot 程式,CI 綠燈。

步驟:
1. 以 Homebrew 安裝 JDK 25(Eclipse Temurin)與 Docker Desktop;確認 `java -version`、`docker run hello-world`
2. 以 Spring Initializr 產生專案:Gradle(Kotlin DSL)、Java 25、Spring Boot 4.1.x;套件:Web、Validation、JDBC API、PostgreSQL Driver、Flyway、Actuator、Testcontainers、Docker Compose Support、SpringDoc OpenAPI
3. 以 Gradle wrapper 固定 Gradle 版本;把實際產生的各套件版本記入 README
4. 一個最小的端點(`GET /ping`)與它的測試;健康檢查直接使用 Actuator 的 `/actuator/health`
5. 建立 GitHub **私人** repo 並推送(建立前先確認);GitHub Actions 執行 `./gradlew build`(含全部測試)。私人 repo 在免費方案每月有 2,000 分鐘的 Actions 額度,足夠使用
6. `.gitignore`、`README.md` 骨架(英文)

**新的 Java 概念**:類別與 `main`、套件(package)、註解(annotation)、Gradle 的角色(對照 `pyproject.toml` + `uv`)

**完成條件**:`./gradlew build` 在本機與 CI 都通過

## 階段 1:金額(`Money`)

**目標**:一個純 Java、不依賴 Spring 的金額型別,後面所有模組都用它。

步驟:
1. `Money`:最小單位的 `long` + `java.util.Currency`;不可變
2. 從字串解析(`"100.50"` + `EUR` → `10050`):小數位數超過該幣別允許的位數時拒收,不捨入
3. 格式化回字串(`10050` → `"100.50"`;JPY 無小數)
4. 加、減、取負;不同幣別相加時丟出例外;溢位時丟出例外(`Math.addExact`)
5. 手續費計算:比例 × 金額,無條件進位到最小單位
6. 匯率換算:金額 × 匯率,無條件捨去到最小單位

**新的 Java 概念**:`record`、靜態工廠方法、例外(checked 與 unchecked)、`equals`/`hashCode`、`long` 溢位、`BigDecimal` 與 `RoundingMode`

**完成條件**:涵蓋 EUR、JPY(0 位)、BHD(3 位)、負數、溢位、邊界捨入的單元測試全數通過

## 階段 2:資料庫、顧客與帳戶

**目標**:可以透過 API 建立顧客、開幣別帳戶、查餘額,資料存在真的 PostgreSQL。

步驟:
1. `compose.yaml`:PostgreSQL;Spring Boot 的 Docker Compose 支援在開發時自動啟動它
2. Flyway `V1`:`customers`、`accounts`(含「同顧客同幣別唯一」限制、餘額欄位)
3. `account` 模組:repository(`JdbcClient`)、service、controller
4. API:`POST /customers`、`POST /customers/{id}/accounts`、`GET /customers/{id}/accounts`
5. 輸入驗證(Bean Validation)與統一的錯誤處理:RFC 9457 problem+json,含穩定的錯誤代碼
6. 測試基底:Testcontainers 啟動 PostgreSQL,每個測試類別共用一個容器

**新的 Java 概念**:依賴注入(對照手動傳參數)、`@Service` / `@RestController`、`Optional`、`record` 當作 API 的請求/回應型別、介面

**完成條件**:API 測試涵蓋成功、404、409(重複開戶)、400(不支援的幣別、格式錯誤)

## 階段 3:複式記帳與入金

**目標**:帳本核心。入金能讓錢進帳戶,每一筆都平衡、可查明細。

步驟:
1. Flyway `V2`:`journal_entries`、`postings`、`deposits`;為支援的幣別建立 `FEE_REVENUE`、`FX_POSITION`、`FUNDING` 系統帳戶
2. `ledger` 模組:`post(entry)`:
   - 檢查每種幣別加總為 0,不平衡即拒絕
   - 依帳戶 id 由小到大以 `SELECT ... FOR UPDATE` 上鎖
   - 檢查顧客帳戶不會變負數
   - 寫入分錄、更新餘額快取
3. 入金:`POST /accounts/{id}/deposits`,含 `Idempotency-Key` 與請求內容雜湊(相同 → 回傳原結果;不同 → 409)
4. `GET /accounts/{id}/statement`:分頁明細
5. 設定鎖等待逾時(`lock_timeout`),逾時轉為 503

**新的 Java 概念**:`@Transactional` 與交易邊界、自訂例外階層、集合與 Stream(`groupingBy`、`sum`)、`Comparator`

**完成條件**:不平衡的分錄被拒;入金重送不重複入帳;明細與餘額一致

## 階段 4:證明帳不會錯

**目標**:以不變量測試與併發測試證明帳本的正確性。這是作品集最重要的部分,放在加入更多功能之前。

步驟:
1. 共用的不變量檢查:所有分錄依幣別加總為 0;每個帳戶餘額快取 = 其分錄加總;顧客帳戶皆不為負
2. jqwik:隨機產生「開戶、入金」序列,每一步後檢查不變量
3. 併發:多執行緒同時對同一帳戶入金與記帳,總額守恆
4. 併發:多個請求同時帶同一個冪等鍵,只產生一筆入金
5. 故意製造錯誤(例如跳過上鎖),確認測試會失敗,證明測試真的有效,之後復原

**新的 Java 概念**:執行緒、`ExecutorService`、`CountDownLatch`、競態條件(race condition)

**完成條件**:故意破壞時測試會紅,正常時穩定綠(連跑多次不偶發失敗)

## 階段 5:匯率與報價

**目標**:抓取真實的 ECB 匯率,產生鎖定的報價。

步驟:
1. `RateSource` 介面;`FrankfurterRateSource` 以 Spring `RestClient` 呼叫 `/v2/providers/ecb/rates?base=EUR`,JSON 數字直接解析為 `BigDecimal`
2. WireMock 測試:正常、逾時、HTTP 500、格式錯誤、缺幣別、負匯率
3. Flyway `V3`:`fx_rates`、`quotes`
4. 定時抓取(`@Scheduled`,預設每小時)並驗證後存入;啟動時抓取一次
5. 交叉匯率(`MathContext.DECIMAL128`)、報價匯率存到小數 10 位、以存下的匯率算目標金額
6. 報價:手續費比例、有效期、匯率最長可用期限皆可設定(`@ConfigurationProperties`);時間來自注入的 `Clock`
7. API:`POST /quotes`、`GET /quotes/{id}`;匯率過舊回傳 503

**新的 Java 概念**:`java.time`(`Instant`、`LocalDate`、`Clock`、`Duration`)、HTTP 用戶端、設定綁定、排程

**完成條件**:固定 `Clock` 與假匯率下的報價金額精確符合手算結果;外部 API 的每種失敗都有測試

## 階段 6:轉帳

**目標**:用報價執行跨幣別轉帳,完整實現設計文件第 8 節的流程。

步驟:
1. Flyway `V4`:`transfers`(冪等鍵唯一、報價 id 唯一)
2. `transfer` 模組:冪等 → 檢查報價 → 上鎖 → 檢查餘額 → 記帳(五行分錄) → 報價改為 `USED`
3. API:`POST /transfers`、`GET /transfers/{id}`
4. 錯誤情境:餘額不足、報價過期、報價已使用、幣別不符、冪等衝突
5. 擴充階段 4 的不變量測試:序列中加入報價與轉帳
6. 併發:兩個不同冪等鍵同時使用同一個報價,只有一個成功;同一帳戶同時多筆轉帳不會超扣

**新的 Java 概念**:把前面學的組合起來;模組之間以介面協作

**完成條件**:設計文件第 5 節的跨幣別範例以 API 端到端重現,分錄與金額完全一致

## 階段 7:打包、文件與公開

**目標**:別人一個指令就能跑起來,README 講清楚設計。

步驟:
1. `Dockerfile`(多階段建置);`compose.yaml` 同時啟動應用程式與 PostgreSQL
2. Swagger UI:每個端點附說明與範例
3. README(英文):一段話說明是什麼、`docker compose up` 快速開始、一個完整的試用流程(開戶 → 入金 → 報價 → 轉帳 → 查明細)、架構圖、設計決定與理由、刻意不做的事
4. CI 加入:以 Docker Compose 啟動整個服務並呼叫一次 API 的 smoke test
5. **把 repo 改為公開**:公開前先確認;公開 repo 的 Actions 不再佔用額度

**完成條件**:在一台乾淨的環境執行 `docker compose up` 後,照 README 的試用流程能走完

## 每個階段的固定檢查

- 全部測試通過;CI 綠燈
- 沒有 `float` / `double` 出現在金額路徑上
- 新的設計決定有寫進 README 或註解的「為什麼」
- commit 訊息用英文,不提及特定公司
