# GitHub authentication and push workflow

GitHub access can use separate credentials. A successful `git push` does not
prove that `gh` or the Codex GitHub connector is authenticated.

| Credential | Used by | Typical storage | What it controls |
| --- | --- | --- | --- |
| GitHub CLI OAuth token | `gh` | Windows Credential Manager / keyring | `gh auth`, `gh repo`, `gh api` |
| Git Credential Manager | Git over HTTPS | Windows Credential Manager | HTTPS `git fetch` / `git push` |
| Codex connector authorization | Codex GitHub integration | Codex settings | Connector operations and scopes |
| SSH key | Git remotes beginning `git@github.com:` | SSH agent or key file/config | SSH `git fetch` / `git push` |

Never paste tokens, passwords, private keys, or full environment dumps into
chat, tickets, or logs.

## Diagnose GitHub CLI on Windows

In PowerShell:

```powershell
gh auth status
$env:GH_TOKEN
$env:GITHUB_TOKEN
Get-Command gh | Select-Object Source
```

The environment variables take precedence over the token in the keyring. If a
stale value is present, clear it for this shell:

```powershell
Remove-Item Env:GH_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:GITHUB_TOKEN -ErrorAction SilentlyContinue
```

If needed, log in interactively with `gh auth login` and choose GitHub.com,
HTTPS, and browser authentication. If `gh` is not on `PATH`, invoke
`C:\Program Files\GitHub CLI\gh.exe` directly. Do not store a token in this
document or a repository.

## Create or connect a private repository

First inspect the working tree, branch, remotes, and recent commits:

```powershell
git status
git branch --show-current
git remote -v
git log --oneline -3
```

Create a private repository and push the current branch with GitHub CLI:

```powershell
gh repo create <repository-name> --source=. --private --push
```

If the private repository already exists and has no `origin`:

```powershell
git remote add origin https://github.com/nhh37740-glitch/<repository-name>.git
git push -u origin <current-branch>
```

Do not assume the branch is `main`. The known project branches at the time of
writing are `agent/agent-learning-roadmap` for `cc-agent-go`, and `master` for
`cc-agent-java` and `cpp`; check each checkout before pushing.

For an SSH remote, use `git@github.com:nhh37740-glitch/<repository-name>.git`
and verify that the selected key is registered with GitHub. Do not convert,
overwrite, or upload private keys as part of routine troubleshooting.

## Troubleshooting order

1. Check `gh auth status` and whether `GH_TOKEN` or `GITHUB_TOKEN` overrides it.
2. Check `git remote -v` to identify HTTPS versus SSH authentication.
3. Run `git ls-remote origin` to test the Git remote without changing it.
4. For HTTPS, let Git Credential Manager perform interactive sign-in. For SSH,
   check the configured identity and `ssh -T git@github.com`.
5. Only create a repository with `gh repo create` when it does not already
   exist. Before retrying a push, confirm the current branch and remote.

`gh` authentication and HTTPS Git authentication are independent: if `gh`
fails, an existing HTTPS remote may still work through Git Credential Manager.
Likewise, a configured Git remote does not create a GitHub repository.
