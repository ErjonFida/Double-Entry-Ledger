# Deploy on AWS

One EC2 instance runs the image CI publishes, Redis, and Caddy, which serves HTTPS with a free Let's Encrypt certificate. PostgreSQL runs on RDS, which only the instance can reach, over SSL.

```
internet --443--> EC2: Caddy --> ledger --> Redis
                                   |
                                   +--SSL--> RDS PostgreSQL
```

Before you start, check what your account's free tier covers in the Billing console, and set a budget alert. Create everything in one region, for example Frankfurt (`eu-central-1`).

## 1. Launch the instance

EC2 → **Launch instance**:

- **Name:** `ledger`
- **Image:** Ubuntu Server 24.04 LTS, 64-bit (x86). CI builds an x86 image, so not Arm.
- **Instance type:** `t3.micro`
- **Key pair:** create one called `ledger` (ED25519, `.pem`) and keep the file it downloads.
- **Network settings:** create a security group that allows SSH from **My IP**, and HTTPS and HTTP from the internet. Caddy needs port 80 to get its certificate and to redirect to HTTPS.
- **Storage:** the default 8 GiB is enough.
- **Advanced details → User data:** paste [cloud-init.yaml](cloud-init.yaml). On first boot it installs Docker and adds 1 GB of swap, since the instance has only 1 GB of memory.

## 2. Give it a fixed address

EC2 → **Elastic IPs** → **Allocate Elastic IP address**, then **Actions → Associate** with the `ledger` instance. Without one, the address changes every time the instance stops.

## 3. Point a hostname at it

Let's Encrypt needs a hostname. At [duckdns.org](https://www.duckdns.org), sign in, add a subdomain such as `yourname-ledger`, and set its IP to the Elastic IP. A domain of your own works too, with an A record pointing to the Elastic IP.

## 4. Create the database

RDS → **Create database**:

- **Creation method:** Standard create. **Engine:** PostgreSQL, the latest 18.x.
- **Template:** Free tier if offered, otherwise Dev/Test with a Single-AZ deployment.
- **DB instance identifier:** `ledger`. **Master username:** `ledger`. **Credentials management:** Self managed.
- **Master password:** run `openssl rand -hex 24` in Git Bash and paste the output. Keep it for step 5.
- **Instance class:** `db.t4g.micro`. **Storage:** 20 GiB gp3, with storage autoscaling turned off.
- **Connectivity:** choose **Connect to an EC2 compute resource** and pick `ledger`. RDS then puts the database in the instance's network and creates security groups that let in only that instance. **Public access:** No.
- **Additional configuration → Initial database name:** `ledger`. Without it, RDS creates no database and the app cannot start.

Creating it takes about 10 minutes. Then copy the **Endpoint** from the database's **Connectivity & security** tab.

## 5. Start the app

From the folder with `ledger.pem`:

```bash
ssh -i ledger.pem ubuntu@ELASTIC_IP
```

On the instance:

```bash
cloud-init status --wait
git clone https://github.com/ErjonFida/Double-Entry-Ledger.git
cd Double-Entry-Ledger/deploy
cp .env.example .env
nano .env
docker compose up -d
docker compose logs -f ledger
```

- **`cloud-init status --wait`** waits until Docker is installed.
- **In `.env`,** fill in your hostname, the RDS endpoint and the password.
- **`docker compose logs -f ledger`** shows the app starting. Wait for `Started LedgerApplication`, then press Ctrl+C.

Open `https://YOUR_HOSTNAME/swagger-ui.html`. Caddy gets the certificate on the first request, which takes a few seconds.

If `ssh` refuses the key with "UNPROTECTED PRIVATE KEY FILE", run this in PowerShell to make the key readable only by you:

```powershell
icacls ledger.pem /inheritance:r /grant:r "$($env:USERNAME):R"
```

## Update

```bash
cd ~/Double-Entry-Ledger/deploy && git pull && docker compose pull && docker compose up -d
```

To roll back, add `TAG=` with a commit's full SHA to `.env` and run `docker compose up -d`. CI tags every image it publishes with its commit's SHA.

## Stop paying for it

- **Instance:** terminate it.
- **Database:** delete it. A stopped RDS database starts again by itself after 7 days.
- **Elastic IP:** release it. It costs money as long as it's allocated, even with nothing attached.
