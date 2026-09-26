# CarbonFlow — Role-Based Access Control (RBAC) Matrix

## 1. Canonical Roles

1. **COMPANY_ADMIN**: Organization administrator. Manages organization configuration, users, facilities, periods, and organizational operations.
2. **SUSTAINABILITY_MANAGER**: Manages sustainability operations, data collection cycles, emission calculations, audit workflows, inventory, and targets.
3. **CARBON_ACCOUNTANT**: Focuses on activity data ingestion, factor selection, calculations, and emission records.
4. **DATA_OWNER**: Responsible for entering and maintaining assigned activity data and attaching evidence.
5. **FACILITY_MANAGER**: Manages facility-specific data collection and equipment operational parameters.
6. **REVIEWER**: Reviews data completeness, inspects calculations and evidence, logs findings, and performs approvals.
7. **MANAGEMENT**: Executive read-oriented persona viewing dashboards, inventory snapshots, targets, and reduction progress.
8. **ASSURANCE_PROVIDER**: Read-oriented auditor persona accessing audit-ready data, calculations, and evidence packages without administrative write rights.
9. **PLATFORM_ADMIN**: Multi-tenant platform operations. Disjoint from organization administration.

*(Note: Per enterprise governance rules, there is strictly no `SUPER_ADMIN` role).*

---

## 2. Canonical Permission Matrix

| Permission Code | Description | CA | SM | CAcc | DO | FM | Rev | Mgt | AP | PA |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `organization.read` | View organization profile & settings | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `organization.update`| Modify settings and legal entities | ✓ | - | - | - | - | - | - | - | - |
| `users.read` | View members and roles | ✓ | ✓ | - | - | - | - | - | - | ✓ |
| `users.create` | Invite organization members | ✓ | - | - | - | - | - | - | - | - |
| `users.update` | Modify user role assignments | ✓ | - | - | - | - | - | - | - | - |
| `users.disable` | Deactivate memberships | ✓ | - | - | - | - | - | - | - | - |
| `facilities.read` | View facilities and details | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `facilities.create` | Register physical facilities | ✓ | ✓ | - | - | - | - | - | - | - |
| `facilities.update` | Edit facility parameters & grid | ✓ | ✓ | - | - | ✓ | - | - | - | - |
| `facilities.delete` | Archive facility | ✓ | - | - | - | - | - | - | - | - |
| `reporting_periods.read` | View accounting periods | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `reporting_periods.create`| Create reporting periods | ✓ | ✓ | - | - | - | - | - | - | - |
| `reporting_periods.update`| Modify reporting periods | ✓ | ✓ | - | - | - | - | - | - | - |
| `activity_data.read` | View activity data records | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `activity_data.create` | Enter activity records | ✓ | ✓ | ✓ | ✓ | ✓ | - | - | - | - |
| `activity_data.update` | Edit draft activity data | ✓ | ✓ | ✓ | ✓ | ✓ | - | - | - | - |
| `activity_data.submit` | Submit data for review | ✓ | ✓ | ✓ | ✓ | ✓ | - | - | - | - |
| `emission_factors.read` | Inspect versioned factor library | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `emission_factors.manage`| Add/version emission factors | ✓ | ✓ | ✓ | - | - | - | - | - | ✓ |
| `calculations.read` | Inspect calculation formula traces | ✓ | ✓ | ✓ | - | - | ✓ | ✓ | ✓ | - |
| `calculations.create` | Run deterministic calculations | ✓ | ✓ | ✓ | - | - | - | - | - | - |
| `evidence.read` | View/download evidence files | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `evidence.upload` | Upload supporting documentation | ✓ | ✓ | ✓ | ✓ | ✓ | - | - | - | - |
| `evidence.delete` | Remove linked evidence | ✓ | ✓ | - | - | - | - | - | - | - |
| `evidence.version` | Upload updated evidence version | ✓ | ✓ | ✓ | ✓ | - | - | - | - | - |
| `audits.read` | View audit runs and checklists | ✓ | ✓ | ✓ | - | - | ✓ | ✓ | ✓ | - |
| `audits.create` | Initiate carbon audit | ✓ | ✓ | - | - | - | - | - | - | - |
| `audits.submit` | Submit audit package | ✓ | ✓ | - | - | - | - | - | - | - |
| `audits.review` | Log review findings and checklist | - | - | - | - | - | ✓ | - | ✓ | - |
| `audits.approve` | Formally approve audit | ✓ | ✓ | - | - | - | ✓ | - | - | - |
| `audits.lock` | Permanently freeze audit period | ✓ | ✓ | - | - | - | - | - | - | - |
| `inventory.read` | View GHG inventory snapshots | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `inventory.create` | Generate inventory snapshot | ✓ | ✓ | ✓ | - | - | - | - | - | - |
| `inventory.lock` | Lock inventory against changes | ✓ | ✓ | - | - | - | - | - | - | - |
| `targets.read` | View reduction targets | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `targets.create` | Define reduction targets | ✓ | ✓ | - | - | - | - | - | - | - |
| `targets.update` | Update target progress | ✓ | ✓ | ✓ | - | - | - | - | - | - |
| `reduction_projects.read` | View reduction projects | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `reduction_projects.create`| Create reduction initiatives | ✓ | ✓ | - | - | - | - | - | - | - |
| `reduction_projects.update`| Update project reduction stats | ✓ | ✓ | ✓ | - | ✓ | - | - | - | - |
| `reports.read` | Export audit packs and CSVs | ✓ | ✓ | ✓ | - | - | ✓ | ✓ | ✓ | - |
| `analytics.read` | View executive dashboards | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | - |
| `platform.tenants.read` | View tenant list across platform | - | - | - | - | - | - | - | - | ✓ |
| `platform.tenants.manage` | Configure platform settings | - | - | - | - | - | - | - | - | ✓ |

*Legend: CA: COMPANY_ADMIN, SM: SUSTAINABILITY_MANAGER, CAcc: CARBON_ACCOUNTANT, DO: DATA_OWNER, FM: FACILITY_MANAGER, Rev: REVIEWER, Mgt: MANAGEMENT, AP: ASSURANCE_PROVIDER, PA: PLATFORM_ADMIN.*
