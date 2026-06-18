# Bicep — Interview Prep Cheat Sheet
**For a 15-yr Java/Azure dev.** Bicep is Azure's IaC language. Covered lightly in `07-devops-cloud/azure.md` (45 mentions); this doc is a standalone deep-dive for interviews.

---

## The Pitch (30 seconds)

> *"Bicep is Azure's domain-specific language (DSL) for Infrastructure as Code. It compiles to ARM templates, reducing boilerplate by ~40%. It's declarative, has strong typing, and integrates with DevOps pipelines (GitHub Actions, Azure Pipelines). I use it to define entire environments—VMs, App Services, databases, networking—as code. Key benefits: version control, repeatable deployments, CI/CD integration, and cost estimation."*

---

## 1. Bicep vs ARM Templates vs Terraform

| Aspect | Bicep | ARM | Terraform |
|---|---|---|---|
| **Language** | Azure-native DSL | JSON (verbose) | HCL (cloud-agnostic) |
| **Lines of code** | ~40% fewer | 100% (baseline) | ~50% of ARM |
| **Type safety** | Strong | Weak (untyped JSON) | Medium |
| **Learning curve** | Moderate | Steep (JSON hell) | Moderate |
| **Multi-cloud** | ❌ Azure only | ❌ Azure only | ✅ AWS, GCP, Azure |
| **Your choice** | **This** | Legacy | Not for Azure-only |

**Interview answer:** *"For Azure-only infrastructure, Bicep is the modern standard. ARM templates are legacy. If your company uses multi-cloud, Terraform is necessary, but Bicep is better for Azure-specific migrations."*

---

## 2. Bicep Structure (Hello World)

```bicep
// main.bicep — Complete small example

param location string = resourceGroup().location
param appName string = 'myapp'
param environment string = 'dev'

var tags = {
  environment: environment
  createdBy: 'bicep'
  createdDate: utcNow('u')
}

var storageAccountName = 'st${replace(appName, '-', '')}${environment}'

resource storageAccount 'Microsoft.Storage/storageAccounts@2021-06-01' = {
  name: storageAccountName
  location: location
  kind: 'StorageV2'
  sku: {
    name: environment == 'prod' ? 'Standard_GRS' : 'Standard_LRS'
  }
  properties: {
    accessTier: 'Hot'
  }
  tags: tags
}

output storageAccountId string = storageAccount.id
output storageAccountName string = storageAccount.name
```

**Key parts:**
- **`param`**: inputs (like function args)
- **`var`**: computed values
- **`resource`**: declare Azure resources
- **`output`**: return values (for chaining bicep files)
- **`tags`**: metadata (cost tracking, automation)

---

## 3. Core Syntax & Patterns

### 3.1 Parameters (Inputs)

```bicep
// Required parameter
param vmName string

// Optional with default
param vmSize string = 'Standard_B2s'

// With constraints
param environment string {
  default: 'dev'
  allowed: ['dev', 'staging', 'prod']
}

// Int, bool, array, object
param replicaCount int = 3
param enableMonitoring bool = true
param allowedSubnets array = ['10.0.0.0/8', '172.16.0.0/12']
param config object = {
  timeout: 30
  retries: 3
}
```

**Interview Q:** *"How do you pass parameters when deploying?"*
**Answer:** `az deployment group create --template-file main.bicep --parameters location=eastus vmSize=Standard_D4s_v3`

### 3.2 Variables (Computed Values)

```bicep
// Simple
var prefix = 'prod'

// String interpolation
var resourceName = '${prefix}-${appName}-${uniqueString(resourceGroup().id)}'

// Conditionals
var sku = environment == 'prod' ? 'Premium' : 'Standard'

// Objects
var networkConfig = {
  vnetName: 'vnet-${appName}'
  subnetName: 'subnet-${appName}'
  addressPrefix: '10.0.0.0/16'
}

// Functions
var timestamp = utcNow('u')
var uniqueSuffix = uniqueString(subscription().id, resourceGroup().id)
```

**Key function:** `uniqueString()` — generates deterministic unique ID (good for storage account names that must be globally unique).

### 3.3 Resource Declaration

```bicep
// Basic
resource myVm 'Microsoft.Compute/virtualMachines@2021-07-01' = {
  name: vmName
  location: location
  properties: {
    osProfile: {
      computerName: vmName
      adminUsername: adminUsername
      adminPassword: adminPassword
    }
    hardwareProfile: {
      vmSize: vmSize
    }
  }
}

// Reference another resource
resource nic 'Microsoft.Network/networkInterfaces@2021-05-01' = {
  name: nicName
  location: location
  properties: {
    networkSecurityGroup: {
      id: nsg.id  // Reference by symbolic name
    }
  }
}

// Conditional resource (deploy only if environment == 'prod')
resource prodDatabase 'Microsoft.Sql/servers/databases@2021-02-01' = if (environment == 'prod') {
  name: '${sqlServer.name}/${dbName}'
  // ...
}
```

**Interview Q:** *"How do you reference outputs from one resource in another?"*
**Answer:** By symbolic name: `nsg.id`, `storageAccount.properties.primaryEndpoints.blob`. Bicep resolves dependencies automatically.

---

## 4. Modules (Code Reuse)

**Problem:** Repeating the same resource groups across environments is duplication.

**Solution:** Modules.

```bicep
// storage.bicep — Reusable module
param location string
param accountName string
param sku string = 'Standard_LRS'

resource storageAccount 'Microsoft.Storage/storageAccounts@2021-06-01' = {
  name: accountName
  location: location
  kind: 'StorageV2'
  sku: {
    name: sku
  }
}

output storageAccountId string = storageAccount.id
output primaryBlobEndpoint string = storageAccount.properties.primaryEndpoints.blob
```

```bicep
// main.bicep — Call the module
module devStorage 'modules/storage.bicep' = {
  name: 'devStorageDeploy'
  params: {
    location: location
    accountName: 'stdevapp001'
    sku: 'Standard_LRS'
  }
}

module prodStorage 'modules/storage.bicep' = {
  name: 'prodStorageDeploy'
  params: {
    location: location
    accountName: 'stprodapp001'
    sku: 'Standard_GRS'
  }
}

output devStorageId string = devStorage.outputs.storageAccountId
output prodStorageId string = prodStorage.outputs.storageAccountId
```

**Interview answer:** *"Modules reduce duplication. I create a base module (e.g., storage, networking, app service) and call it multiple times with different parameters. This scales from 5 to 500 resources without multiplying code."*

---

## 5. Parameter Files (Environment Separation)

Keep Bicep DRY by externalizing parameters per environment.

```bicep
// main.bicep
param location string
param environment string
param vmSize string
param vmCount int
```

```json
// parameters.dev.json
{
  "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#",
  "contentVersion": "1.0.0.0",
  "parameters": {
    "location": { "value": "eastus" },
    "environment": { "value": "dev" },
    "vmSize": { "value": "Standard_B2s" },
    "vmCount": { "value": 1 }
  }
}
```

```json
// parameters.prod.json
{
  "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#",
  "contentVersion": "1.0.0.0",
  "parameters": {
    "location": { "value": "eastus" },
    "environment": { "value": "prod" },
    "vmSize": { "value": "Standard_D4s_v3" },
    "vmCount": { "value": 3 }
  }
}
```

**Deploy:**
```bash
# Dev
az deployment group create \
  --resource-group rg-dev \
  --template-file main.bicep \
  --parameters parameters.dev.json

# Prod
az deployment group create \
  --resource-group rg-prod \
  --template-file main.bicep \
  --parameters parameters.prod.json
```

**Interview answer:** *"Parameter files let us version-control environment-specific configs separately from templates. Single template, multiple environments—no duplication."*

---

## 6. Key Patterns & Anti-Patterns

### ✅ DO

```bicep
// 1. Use symbolic names for references (auto-dependency tracking)
resource vnet 'Microsoft.Network/virtualNetworks@2021-05-01' = {
  name: vnetName
  // ...
}

resource subnet 'Microsoft.Network/virtualNetworks/subnets@2021-05-01' = {
  parent: vnet  // Explicit parent for clarity
  name: subnetName
  // ...
}

// 2. Tag all resources
tags: {
  environment: environment
  costCenter: 'engineering'
  deploymentId: deployment().name
}

// 3. Use uniqueString() for globally unique names
var uniqueSuffix = uniqueString(subscription().id, resourceGroup().id)

// 4. Conditional resources with `if`
resource prodResource 'Type@version' = if (isProd) { ... }

// 5. Return outputs for chaining
output resourceId string = myResource.id
output resourceEndpoint string = myResource.properties.endpoint
```

### ❌ DON'T

```bicep
// 1. Don't hardcode values
var appName = 'my-hardcoded-app'  // ❌ Not reusable

// 2. Don't use weak naming
resource r1 'Type@v' = { name: 'resource1' }  // ❌ Unclear

// 3. Don't embed secrets in Bicep
param adminPassword string = 'MyPassword123!'  // ❌ NEVER

// 4. Don't ignore tagging (costs money to clean up)
resource vm 'Type@v' = {
  // ❌ No tags → untracked → no cost attribution
}

// 5. Don't create circular dependencies
// resource A depends on B depends on A  ❌
```

---

## 7. Common Azure Resources (Bicep Snippet Library)

### Virtual Machine
```bicep
resource vm 'Microsoft.Compute/virtualMachines@2021-07-01' = {
  name: vmName
  location: location
  properties: {
    hardwareProfile: { vmSize: 'Standard_B2s' }
    osProfile: {
      computerName: computerName
      adminUsername: adminUsername
      linuxConfiguration: {
        disablePasswordAuthentication: true
        ssh: {
          publicKeys: [
            {
              path: '/home/${adminUsername}/.ssh/authorized_keys'
              keyData: sshPublicKey
            }
          ]
        }
      }
    }
    storageProfile: {
      imageReference: {
        publisher: 'Canonical'
        offer: '0001-com-ubuntu-server-jammy'
        sku: '22_04-lts-gen2'
        version: 'latest'
      }
    }
    networkProfile: {
      networkInterfaces: [
        { id: nic.id }
      ]
    }
  }
}
```

### App Service (Web App)
```bicep
resource appServicePlan 'Microsoft.Web/serverfarms@2021-02-01' = {
  name: appServicePlanName
  location: location
  sku: {
    name: environment == 'prod' ? 'P1V2' : 'B1'
    capacity: environment == 'prod' ? 2 : 1
  }
}

resource webApp 'Microsoft.Web/sites@2021-02-01' = {
  name: webAppName
  location: location
  properties: {
    serverFarmId: appServicePlan.id
    siteConfig: {
      netFrameworkVersion: 'v6.0'
      use32BitWorkerProcess: false
    }
  }
}
```

### SQL Database
```bicep
resource sqlServer 'Microsoft.Sql/servers@2021-02-01' = {
  name: sqlServerName
  location: location
  properties: {
    administratorLogin: adminUsername
    administratorLoginPassword: adminPassword
    version: '12.0'
  }
}

resource database 'Microsoft.Sql/servers/databases@2021-02-01' = {
  parent: sqlServer
  name: databaseName
  location: location
  sku: {
    name: environment == 'prod' ? 'S3' : 'S0'
  }
}
```

### Storage Account
```bicep
resource storage 'Microsoft.Storage/storageAccounts@2021-06-01' = {
  name: storageAccountName
  location: location
  kind: 'StorageV2'
  sku: {
    name: environment == 'prod' ? 'Standard_GRS' : 'Standard_LRS'
  }
  properties: {
    accessTier: 'Hot'
    minimumTlsVersion: 'TLS1_2'
  }
}
```

---

## 8. GitHub Actions Integration (Your Stack)

Bicep + GitHub Actions = automated deployments.

```yaml
# .github/workflows/deploy-azure-infra.yml
name: Deploy Azure Infrastructure

on:
  push:
    branches: [main]
    paths:
      - 'infra/bicep/**'
  pull_request:
    branches: [main]

jobs:
  validate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Run Bicep Linter
        run: |
          az bicep build --file infra/bicep/main.bicep --outdir .
      
      - name: Validate ARM Template
        run: |
          az deployment group validate \
            --resource-group rg-${{ github.ref_name }} \
            --template-file main.json \
            --parameters parameters.json

  deploy:
    needs: validate
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Azure Login
        uses: azure/login@v1
        with:
          creds: ${{ secrets.AZURE_CREDENTIALS }}
      
      - name: Deploy Bicep
        run: |
          az deployment group create \
            --resource-group rg-prod \
            --template-file infra/bicep/main.bicep \
            --parameters parameters.prod.json \
            --mode Incremental
```

**Interview Q:** *"How do you integrate Bicep deployments into CI/CD?"*
**Answer:** *"GitHub Actions runs `az bicep build` to lint, validates the template with `az deployment group validate`, and on merge to main, deploys to prod with `az deployment group create`. Secrets are stored in GitHub repo settings."*

---

## 9. Interview Questions & Answers

### Q1. What is Bicep and why use it over ARM templates?
**A:** Bicep is Azure's DSL for IaC. It compiles to ARM templates but reduces boilerplate ~40% with cleaner syntax, strong typing, and module support. ARM templates are JSON-based and harder to read/maintain. For Azure-only infrastructure, Bicep is the modern standard. For multi-cloud, Terraform is necessary.

### Q2. How do you handle secrets (passwords, API keys) in Bicep?
**A:** Never hardcode. Use Azure Key Vault. Pass the Key Vault reference as a parameter, or use `reference()` function to pull secrets at deployment time:
```bicep
param keyVaultName string
resource kv 'Microsoft.KeyVault/vaults@2021-06-01' existing = {
  name: keyVaultName
}
param dbPassword string = kv.getSecret('dbPassword')
```
For GitHub Actions, store secrets in repo settings and pass via environment variables.

### Q3. Explain parameter files. Why separate from the template?
**A:** Parameter files let you keep configs per environment (dev.json, prod.json) separate from the template. Single template, multiple environments. Easier to review changes, version control, and test different configs without modifying the template.

### Q4. How does Bicep handle dependencies between resources?
**A:** Automatically. If you reference one resource from another (e.g., `subnet.id = vnet.id`), Bicep infers a dependency and ensures deployment order. You can also use `dependsOn: [resource.id]` for explicit ordering.

### Q5. What's the difference between `mode: Incremental` vs `mode: Complete`?
**A:**
- **Incremental:** Deploy only new/changed resources. Existing untouched resources remain. Safer for ongoing deployments.
- **Complete:** Delete any resources in the resource group NOT in the template. Dangerous; use only for isolated groups.

For production, use **Incremental** unless you're certain.

### Q6. How do you handle scaling (e.g., deploy 5 VMs instead of 1)?
**A:** Use loops:
```bicep
param vmCount int = 3

resource vms 'Microsoft.Compute/virtualMachines@2021-07-01' = [for i in range(0, vmCount): {
  name: '${vmName}-${i}'
  location: location
  // ...
}]
```
Or use a module in a loop:
```bicep
module vmModule 'modules/vm.bicep' = [for i in range(0, vmCount): { ... }]
```

### Q7. Design a multi-environment Bicep setup for a 3-tier app (web, app, db).
**A:**
```
infra/
├── bicep/
│   ├── main.bicep              # Orchestrates all modules
│   ├── modules/
│   │   ├── web-tier.bicep      # App Service + CDN
│   │   ├── app-tier.bicep      # App Service (backend)
│   │   ├── db-tier.bicep       # SQL Server + databases
│   │   └── networking.bicep    # VNet, subnets, NSGs
│   └── outputs.bicep           # Aggregate outputs
├── parameters.dev.json
├── parameters.staging.json
└── parameters.prod.json
```

main.bicep calls modules with environment-specific params. Deploy: `az deployment group create ... --parameters parameters.prod.json`

---

## 10. Common Mistakes & Recovery

| Mistake | Symptom | Fix |
|---|---|---|
| Hardcoded global name (storage account) | Deployment fails: "already exists" | Use `uniqueString()` |
| Missing dependencies | Resource fails to provision | Add `dependsOn` or reference another resource to infer |
| Secrets in Bicep | Leaked in source control | Use Key Vault references; never hardcode |
| Wrong API version | Resource API not recognized | Check `az provider show --namespace Microsoft.Compute` |
| Parameter mismatch | Template errors | Ensure param types match in JSON files (string vs int) |

---

## Study Plan (2 hours for interviews)

| Time | Topic |
|---|---|
| 30 min | Read sections 1–3 (what Bicep is, syntax) |
| 30 min | Write a small template (storage account + param file) |
| 30 min | Read sections 4–5 (modules, parameter files) |
| 30 min | Read sections 9–10 (interview Q's, common mistakes) |

**By the end:** You can design a multi-environment Bicep setup, discuss modules/parameters, and recover from common errors.

---

## Resources

- [Bicep Docs](https://learn.microsoft.com/en-us/azure/azure-resource-manager/bicep/)
- [Bicep CLI](https://learn.microsoft.com/en-us/azure/azure-resource-manager/bicep/install)
- [Azure Resource Reference](https://learn.microsoft.com/en-us/azure/templates/)
- [Bicep Visual Studio Code Extension](https://marketplace.visualstudio.com/items?itemName=ms-azuretools.vscode-bicep)

---

**You've got Bicep. Pair this with the main guides' Azure section and you're solid on IaC.**
