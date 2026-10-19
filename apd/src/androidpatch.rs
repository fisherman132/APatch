use std::fs::{self, File, Permissions};
use std::io::{Cursor, Read, Write};
use std::os::unix::fs::{MetadataExt, PermissionsExt, symlink};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result, ensure};

use crate::{defs, restorecon, sepolicy, utils};

const APD_LINK_PATH: &str = "/data/adb/ap/bin/apd";
const BUSYBOX_PATH: &str = "/data/adb/ap/bin/busybox";
const KPTOOLS_PATH: &str = "/data/adb/ap/bin/kptools";
const PACKAGE_CONFIG_PATH: &str = "/data/adb/ap/package_config";
const SU_PATH_PATH: &str = "/data/adb/ap/su_path";
const VERSION_PATH: &str = "/data/adb/ap/version";
const LEGACY_SU_PATH: &str = "/system/bin/su";
const LEGACY_KPATCH_PATH: &str = "/data/adb/kpatch";
const PRODUCT_STAGING_ROOT: &str = "/data/adb/ap/.androidpatch-product";
const PRODUCT_BACKUP_ROOT: &str = "/data/adb/ap/.androidpatch-product-backup";
const PROFILE_DATA_STAGING_ROOT: &str = "/data/adb/ap/.androidpatch-profile-data";
const PROFILE_DATA_BACKUP_ROOT: &str = "/data/adb/ap/.androidpatch-profile-data-backup";
const PROFILE_MODULE_STAGING_ROOT: &str = "/data/adb/ap/.androidpatch-profile-module";
const PROFILE_MODULE_BACKUP_ROOT: &str = "/data/adb/ap/.androidpatch-profile-module-backup";
const PROFILE_STATE_ROOT: &str = "/data/adb/ksh-profile-controller";
const NEED_REBOOT_PATH: &str = "/dev/.need_reboot";
const PROFILE_SERVICE: &str = "/data/adb/modules/device_profile_hook/bin/ksh-profile-service";
const PROFILE_MODULE_SERVICE: &str = "/data/adb/modules/device_profile_hook/service.sh";
const HOOK_BASE_SERVICE: &str = "/data/adb/modules/ksh_hook_base/service.sh";
const HOOK_BASE_STATUS: &str = "/data/adb/ksh-hook-base/status";
const DEVICE_PROFILE_STATUS: &str = "/data/adb/device-profile-hook/status";
const BOOT_ID_PATH: &str = "/proc/sys/kernel/random/boot_id";
const PROFILE_SERVICE_PID: &str = "/data/adb/ksh-profile-controller/service.pid";
const LEGACY_PROFILE_SERVICE: &str =
    "/data/adb/modules/ksh_profile_controller/bin/ksh-profile-service";
const LEGACY_CONTROLLER_BROKER: &str =
    "/data/adb/modules/ksh_profile_controller/bin/ksh-instance-broker";

const KSH_HOOK_BASE_ZIP: &[u8] =
    include_bytes!("../../../../profile/ksh_hook_base/build/ksh_hook_base.zip");
const DEVICE_PROFILE_HOOK_ZIP: &[u8] =
    include_bytes!("../../../../profile/device_profile_hook/build/device_profile_hook.zip");
const RETIRED_PRODUCT_MODULES: &[&str] = &[
    "ksh_managed_runtime",
    "device_profile_ksh",
    "ksh_device_profiles",
    "ksh_profile_controller",
];

struct ProductModule {
    id: &'static str,
    archive_name: &'static str,
    archive: &'static [u8],
    required_paths: &'static [&'static str],
    executable_paths: &'static [&'static str],
    private_paths: &'static [&'static str],
}

const PRODUCT_MODULES: &[ProductModule] = &[
    ProductModule {
        id: "ksh_hook_base",
        archive_name: "ksh_hook_base.zip",
        archive: KSH_HOOK_BASE_ZIP,
        required_paths: &[
            "module.prop",
            "kpm/ksh_core.kpm",
            "kpm/ksh_core.sha256",
            "kpm/authorization.token",
            "zygisk/arm64-v8a.so",
            "lib64/libzygisk.so",
            "lib/libzygisk.so",
            "resident/arm64.so",
            "trust/device-profile.pub",
            "trust/policy.properties",
            "post-fs-data.sh",
            "bin/ksh-hook-bootstrap",
            "bin/ksh-managed-service.sh",
            "bin/zygiskd64",
            "bin/zygiskd32",
            "bin/zygisk-ptrace64",
            "bin/zygisk-ptrace32",
        ],
        executable_paths: &[
            "post-fs-data.sh",
            "service.sh",
            "bin/ksh_module_push",
            "bin/ksh-hook-bootstrap",
            "bin/ksh-managed-service.sh",
            "bin/zygiskd64",
            "bin/zygiskd32",
            "bin/zygisk-ptrace64",
            "bin/zygisk-ptrace32",
        ],
        private_paths: &[
            "kpm/ksh_core.kpm",
            "kpm/ksh_core.sha256",
            "kpm/authorization.token",
        ],
    },
    ProductModule {
        id: "device_profile_hook",
        archive_name: "device_profile_hook.zip",
        archive: DEVICE_PROFILE_HOOK_ZIP,
        required_paths: &[
            "module.prop",
            "ksh/classes.dex",
            "ksh/module.properties",
            "kpm/device_profile.kpm",
            "kpm/device_profile.sha256",
            "kpm/authorization.token",
            "profile.index",
            "bin/ksh-device-profile-data",
            "bin/ksh-kpm-bootstrap",
            "policy/device-profile.pub",
            "policy/profile_source.sha256",
            "policy/surface_admission.conf",
            "bin/ksh-slot-worker",
            "bin/ksh-slot-worker-lib.sh",
        ],
        executable_paths: &[
            "service.sh",
            "action.sh",
            "uninstall.sh",
            "bin/ksh-profilectl",
            "bin/ksh-profile-service",
            "bin/ksh-profile-controller-service.sh",
            "bin/ksh-profile-controller-uninstall.sh",
            "bin/ksh-kpm-bootstrap",
            "bin/ksh-device-profile-data",
            "lib/kernel_profile.sh",
        ],
        private_paths: &[
            "bin/ksh-slot-worker",
            "bin/ksh-slot-worker-lib.sh",
            "bin/ksh-network-cert-worker",
            "kpm/device_profile.kpm",
            "kpm/device_profile.sha256",
            "kpm/authorization.token",
        ],
    },
];

fn remove_path_no_follow(path: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || metadata.is_file() => {
            fs::remove_file(path)?;
        }
        Ok(metadata) if metadata.is_dir() => {
            fs::remove_dir_all(path)?;
        }
        Ok(_) => anyhow::bail!("Unsafe AndroidPatch product path {}", path.display()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    Ok(())
}

fn safe_relative_archive_path(path: &Path) -> bool {
    !path.as_os_str().is_empty()
        && !path.is_absolute()
        && path.components().all(|component| {
            matches!(
                component,
                std::path::Component::Normal(_) | std::path::Component::CurDir
            )
        })
}

fn archive_module_id(archive: &[u8]) -> Result<String> {
    let mut zip = zip::ZipArchive::new(Cursor::new(archive))?;
    let mut module_prop = String::new();
    zip.by_name("module.prop")?
        .read_to_string(&mut module_prop)?;
    module_prop
        .lines()
        .find_map(|line| line.strip_prefix("id="))
        .map(str::trim)
        .filter(|id| !id.is_empty())
        .map(str::to_owned)
        .context("Built-in product module lacks an id")
}

fn extract_product_module(module: &ProductModule, destination: &Path) -> Result<()> {
    ensure!(
        archive_module_id(module.archive)?.as_str() == module.id,
        "Built-in product module id mismatch for {}",
        module.archive_name
    );
    fs::create_dir_all(destination)?;
    fs::set_permissions(destination, Permissions::from_mode(0o755))?;
    let mut archive = zip::ZipArchive::new(Cursor::new(module.archive))?;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index)?;
        let path = entry
            .enclosed_name()
            .context("Unsafe path in built-in product module")?;
        ensure!(
            safe_relative_archive_path(&path),
            "Unsafe product module path"
        );
        let unix_mode = entry.unix_mode().unwrap_or(0o644);
        ensure!(
            unix_mode & 0o170000 != 0o120000,
            "Product module symlink rejected"
        );
        let output = destination.join(path);
        ensure!(
            output.starts_with(destination),
            "Product module escaped staging root"
        );
        if entry.is_dir() {
            fs::create_dir_all(&output)?;
            fs::set_permissions(&output, Permissions::from_mode(0o755))?;
            continue;
        }
        if let Some(parent) = output.parent() {
            fs::create_dir_all(parent)?;
            fs::set_permissions(parent, Permissions::from_mode(0o755))?;
        }
        let temporary = output.with_extension(format!("new.{}", std::process::id()));
        remove_path_no_follow(&temporary)?;
        let mut file = File::create(&temporary)?;
        std::io::copy(&mut entry, &mut file)?;
        file.flush()?;
        file.sync_all()?;
        fs::set_permissions(&temporary, Permissions::from_mode(0o644))?;
        fs::rename(&temporary, &output)?;
    }
    for executable in module.executable_paths {
        let path = destination.join(executable);
        ensure!(path.is_file(), "Missing built-in executable {executable}");
        fs::set_permissions(path, Permissions::from_mode(0o755))?;
    }
    for private in module.private_paths {
        let path = destination.join(private);
        ensure!(path.is_file(), "Missing built-in private file {private}");
        fs::set_permissions(path, Permissions::from_mode(0o600))?;
    }
    for required in module.required_paths {
        ensure!(
            destination.join(required).is_file(),
            "Missing built-in product file {required}"
        );
    }
    restorecon::restore_syscon(destination)?;
    Ok(())
}

fn sibling_binary(name: &str, source_dir: Option<&Path>) -> Result<PathBuf> {
    if let Some(parent) = source_dir {
        let source = parent.join(name);
        ensure!(source.is_file(), "Missing AndroidPatch asset {name}");
        return Ok(source);
    }
    if let Some(parent) = std::env::var_os("APATCH_MANAGER_LIB_DIR") {
        let source = PathBuf::from(parent).join(name);
        ensure!(source.is_file(), "Missing AndroidPatch asset {name}");
        return Ok(source);
    }
    let current_exe = std::env::current_exe().context("Cannot resolve AndroidPatch installer")?;
    let parent = current_exe
        .parent()
        .context("AndroidPatch installer has no parent directory")?;
    let source = parent.join(name);
    ensure!(source.is_file(), "Missing AndroidPatch asset {name}");
    Ok(source)
}

fn copy_binary(source: &Path, destination: &Path) -> Result<()> {
    let temporary = destination.with_extension(format!("tmp.{}", std::process::id()));
    let _ = fs::remove_file(&temporary);
    fs::copy(source, &temporary).with_context(|| {
        format!(
            "Cannot copy {} to {}",
            source.display(),
            destination.display()
        )
    })?;
    fs::set_permissions(&temporary, Permissions::from_mode(0o755))?;
    fs::rename(&temporary, destination).with_context(|| {
        format!(
            "Cannot replace {} with {}",
            destination.display(),
            source.display()
        )
    })?;
    Ok(())
}

fn replace_symlink(target: &str, link: &str) -> Result<()> {
    if fs::symlink_metadata(link).is_ok() {
        fs::remove_file(link).with_context(|| format!("Cannot replace {link}"))?;
    }
    symlink(target, link).with_context(|| format!("Cannot create {link}"))
}

fn verify_product_module_at(module: &ProductModule, root: &Path) -> Result<()> {
    let root_metadata = match fs::symlink_metadata(&root) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            anyhow::bail!("{} is missing", module.id)
        }
        Err(error) => return Err(error.into()),
    };
    ensure!(
        root_metadata.is_dir() && !root_metadata.file_type().is_symlink(),
        "{} root is not a safe directory",
        module.id
    );
    ensure!(
        root_metadata.uid() == 0 && root_metadata.gid() == 0,
        "{} root owner is {}:{}",
        module.id,
        root_metadata.uid(),
        root_metadata.gid()
    );
    ensure!(!root.join("disable").exists(), "{} is disabled", module.id);
    ensure!(
        !root.join("remove").exists(),
        "{} is pending removal",
        module.id
    );

    let mut archive = zip::ZipArchive::new(Cursor::new(module.archive))?;
    let mut archive_buffer = [0u8; 32 * 1024];
    let mut installed_buffer = [0u8; 32 * 1024];
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index)?;
        let relative = entry
            .enclosed_name()
            .context("Unsafe path in built-in product module")?;
        ensure!(
            safe_relative_archive_path(&relative),
            "{} archive path is unsafe: {}",
            module.id,
            relative.display()
        );
        let installed = root.join(relative);
        let metadata = match fs::symlink_metadata(&installed) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                anyhow::bail!("{} is missing {}", module.id, installed.display())
            }
            Err(error) => return Err(error.into()),
        };
        ensure!(
            !metadata.file_type().is_symlink(),
            "{} contains symlink {}",
            module.id,
            installed.display()
        );
        ensure!(
            metadata.uid() == 0 && metadata.gid() == 0,
            "{} owner mismatch at {}: {}:{}",
            module.id,
            installed.display(),
            metadata.uid(),
            metadata.gid()
        );
        if entry.is_dir() {
            ensure!(
                metadata.is_dir(),
                "{} expected directory at {}",
                module.id,
                installed.display()
            );
            continue;
        }
        ensure!(
            metadata.is_file(),
            "{} expected regular file at {}",
            module.id,
            installed.display()
        );
        ensure!(
            metadata.len() == entry.size(),
            "{} size mismatch at {}: installed={} expected={}",
            module.id,
            installed.display(),
            metadata.len(),
            entry.size()
        );
        let mut file = File::open(&installed)?;
        loop {
            let archive_count = entry.read(&mut archive_buffer)?;
            if archive_count == 0 {
                break;
            }
            file.read_exact(&mut installed_buffer[..archive_count])?;
            if archive_buffer[..archive_count] != installed_buffer[..archive_count] {
                anyhow::bail!("{} content mismatch at {}", module.id, installed.display());
            }
        }
    }
    for executable in module.executable_paths {
        let metadata = fs::symlink_metadata(root.join(executable))?;
        ensure!(
            metadata.is_file()
                && !metadata.file_type().is_symlink()
                && metadata.mode() & 0o111 != 0,
            "{} executable is invalid: {}",
            module.id,
            executable
        );
    }
    for private in module.private_paths {
        let metadata = fs::symlink_metadata(root.join(private))?;
        ensure!(
            metadata.is_file()
                && !metadata.file_type().is_symlink()
                && metadata.mode() & 0o777 == 0o600,
            "{} private file is invalid: {}",
            module.id,
            private
        );
    }
    Ok(())
}

fn verify_product_module(module: &ProductModule) -> Result<()> {
    verify_product_module_at(module, &Path::new(defs::MODULE_DIR).join(module.id))
}

fn verify_product_modules() -> Result<()> {
    for module in PRODUCT_MODULES {
        verify_product_module(module)?;
    }
    Ok(())
}

fn product_modules_match() -> Result<bool> {
    match verify_product_modules() {
        Ok(()) => Ok(true),
        Err(error) => {
            log::warn!("Built-in product module verification requested repair: {error:#}");
            Ok(false)
        }
    }
}

fn copy_root_owned_tree(source: &Path, destination: &Path, budget: &mut usize) -> Result<()> {
    let metadata = fs::symlink_metadata(source)?;
    ensure!(
        metadata.uid() == 0 && !metadata.file_type().is_symlink(),
        "Unsafe root-owned product state {}",
        source.display()
    );
    ensure!(*budget > 0, "Product state entry limit exceeded");
    *budget -= 1;
    if metadata.is_dir() {
        fs::create_dir_all(destination)?;
        fs::set_permissions(destination, Permissions::from_mode(metadata.mode() & 0o777))?;
        for entry in fs::read_dir(source)? {
            let entry = entry?;
            copy_root_owned_tree(&entry.path(), &destination.join(entry.file_name()), budget)?;
        }
        return Ok(());
    }
    ensure!(metadata.is_file(), "Unsupported product state object");
    ensure!(
        metadata.len() <= 16 * 1024 * 1024,
        "Product state file too large"
    );
    fs::copy(source, destination)?;
    fs::set_permissions(destination, Permissions::from_mode(metadata.mode() & 0o777))?;
    Ok(())
}

fn preserve_profile_state(staging_root: &Path) -> Result<()> {
    let source = Path::new(defs::MODULE_DIR).join("device_profile_hook/ksh/state");
    let metadata = match fs::symlink_metadata(&source) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        metadata.is_dir()
            && !metadata.file_type().is_symlink()
            && metadata.uid() == 0
            && metadata.gid() == 0,
        "Unsafe existing device-profile state"
    );
    let destination = staging_root.join("device_profile_hook/ksh/state");
    remove_path_no_follow(&destination)?;
    let mut entry_budget = 4096;
    copy_root_owned_tree(&source, &destination, &mut entry_budget)
}

fn protect_profile_state(path: &Path, budget: &mut usize) -> Result<()> {
    let metadata = fs::symlink_metadata(path)?;
    ensure!(
        metadata.uid() == 0 && metadata.gid() == 0 && !metadata.file_type().is_symlink(),
        "Unsafe device-profile state {}",
        path.display()
    );
    ensure!(*budget > 0, "Product state entry limit exceeded");
    *budget -= 1;
    if metadata.is_dir() {
        fs::set_permissions(path, Permissions::from_mode(0o700))?;
        for entry in fs::read_dir(path)? {
            protect_profile_state(&entry?.path(), budget)?;
        }
    } else {
        ensure!(
            metadata.is_file(),
            "Unsupported device-profile state object"
        );
        fs::set_permissions(path, Permissions::from_mode(0o600))?;
    }
    Ok(())
}

fn stop_profile_service() {
    for service in [PROFILE_SERVICE, LEGACY_PROFILE_SERVICE] {
        if !Path::new(service).is_file() {
            continue;
        }
        let _ = Command::new(service)
            .arg("--stop")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    if Path::new(LEGACY_CONTROLLER_BROKER).is_file() {
        let _ = Command::new(LEGACY_CONTROLLER_BROKER)
            .arg("--stop")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
    for _ in 0..100 {
        if !profile_service_running() {
            return;
        }
        thread::sleep(Duration::from_millis(10));
    }
}

fn profile_service_running() -> bool {
    let pid = match fs::read_to_string(PROFILE_SERVICE_PID)
        .ok()
        .and_then(|value| value.trim().parse::<u32>().ok())
    {
        Some(pid) if pid > 1 => pid,
        _ => return false,
    };
    fs::read_link(format!("/proc/{pid}/exe"))
        .map(|path| path == Path::new(PROFILE_SERVICE))
        .unwrap_or(false)
}

fn receipt_has_exact_lines(path: &str, required: &[&str]) -> Result<bool> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        metadata.is_file() && !metadata.file_type().is_symlink() && metadata.uid() == 0,
        "Unsafe product service receipt {path}"
    );
    let receipt = fs::read_to_string(path)?;
    Ok(required
        .iter()
        .all(|expected| receipt.lines().any(|line| line == *expected)))
}

fn verify_product_service_receipts() -> Result<()> {
    let boot_id = fs::read_to_string(BOOT_ID_PATH)?;
    let boot_line = format!("boot_id={}", boot_id.trim());
    ensure!(
        receipt_has_exact_lines(
            HOOK_BASE_STATUS,
            &[
                "schema=ksh-hook-base-status/v1",
                &boot_line,
                "state=ready",
                "runtime=active",
                "route_mask=63",
                "zygote_loader=ready",
                "managed_runtime=ready",
            ],
        )?,
        "KSH Hook base did not publish a complete current-boot receipt"
    );
    ensure!(
        receipt_has_exact_lines(
            DEVICE_PROFILE_STATUS,
            &[
                "schema=ksh-device-profile-status/v1",
                &boot_line,
                "state=ready",
                "hook_base=ready",
                "controller=reconciled",
            ],
        )?,
        "device-profile Hook did not publish a complete current-boot receipt"
    );
    Ok(())
}

fn start_product_services() -> Result<()> {
    fs::create_dir_all(PROFILE_STATE_ROOT)?;
    fs::set_permissions(PROFILE_STATE_ROOT, Permissions::from_mode(0o700))?;
    restorecon::lsetfilecon(PROFILE_STATE_ROOT, restorecon::ADB_CON)?;

    let runtime_service = Path::new(HOOK_BASE_SERVICE);
    ensure!(
        runtime_service.is_file(),
        "KSH Hook base service is missing"
    );
    Command::new("/system/bin/sh")
        .arg(&runtime_service)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .context("Cannot start KSH runtime service")?;

    /*
     * The mailbox daemon can outlive a failed one-shot module service.  Its
     * presence therefore does not prove that the current-boot reconciliation
     * receipt was published.  Always rerun the idempotent module service; its
     * own lock suppresses duplicate reconcilers while allowing recovery from
     * a stale blocked receipt.
     */
    ensure!(
        Path::new(PROFILE_MODULE_SERVICE).is_file(),
        "device-profile module service is missing"
    );
    let status = Command::new("/system/bin/sh")
        .arg(PROFILE_MODULE_SERVICE)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("Cannot start device-profile module service")?;
    ensure!(status.success(), "device-profile module service failed");
    for _ in 0..100 {
        if profile_service_running() {
            break;
        }
        thread::sleep(Duration::from_millis(100));
    }
    ensure!(
        profile_service_running(),
        "device-profile service did not start"
    );
    verify_product_service_receipts()?;
    Ok(())
}

fn recover_interrupted_product_transaction() -> Result<()> {
    let backup_root = Path::new(PRODUCT_BACKUP_ROOT);
    let metadata = match fs::symlink_metadata(backup_root) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        metadata.is_dir()
            && !metadata.file_type().is_symlink()
            && metadata.uid() == 0
            && metadata.gid() == 0,
        "Unsafe AndroidPatch product backup"
    );
    let module_root = Path::new(defs::MODULE_DIR);
    for product in PRODUCT_MODULES {
        let current = module_root.join(product.id);
        let backup = backup_root.join(product.id);
        let absent = backup_root.join(format!("{}.absent", product.id));
        if fs::symlink_metadata(&backup).is_ok() {
            remove_path_no_follow(&current)?;
            fs::rename(&backup, &current)?;
        } else if fs::symlink_metadata(&absent).is_ok() {
            remove_path_no_follow(&current)?;
        } else {
            // An interruption may happen after the backup directory is
            // created but before the absent marker is published. If there is
            // no current or backup directory, the old module was absent. If
            // current still exists, no replacement was published yet.
            let _ = fs::symlink_metadata(&current);
        }
    }
    remove_path_no_follow(backup_root)
}

fn install_product_modules() -> Result<()> {
    recover_interrupted_product_transaction()?;
    if product_modules_match()? {
        if Path::new(NEED_REBOOT_PATH).exists() {
            return Ok(());
        }
        return start_product_services();
    }

    let module_root = Path::new(defs::MODULE_DIR);
    match fs::symlink_metadata(module_root) {
        Ok(metadata) => ensure!(
            metadata.is_dir() && !metadata.file_type().is_symlink(),
            "Unsafe APatch module root"
        ),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::create_dir_all(module_root)?;
        }
        Err(error) => return Err(error.into()),
    }
    fs::set_permissions(module_root, Permissions::from_mode(0o700))?;
    let staging_root = Path::new(PRODUCT_STAGING_ROOT);
    let backup_root = Path::new(PRODUCT_BACKUP_ROOT);
    remove_path_no_follow(staging_root)?;
    remove_path_no_follow(backup_root)?;
    fs::create_dir_all(staging_root)?;
    fs::create_dir_all(backup_root)?;
    fs::set_permissions(staging_root, Permissions::from_mode(0o700))?;
    fs::set_permissions(backup_root, Permissions::from_mode(0o700))?;
    for product in PRODUCT_MODULES {
        let destination = staging_root.join(product.id);
        extract_product_module(product, &destination)?;
        verify_product_module_at(product, &destination)
            .with_context(|| format!("{} staging verification failed", product.id))?;
    }
    preserve_profile_state(staging_root)?;
    let mut state_budget = 4096;
    protect_profile_state(
        &staging_root.join("device_profile_hook/ksh/state"),
        &mut state_budget,
    )?;

    stop_profile_service();
    let mut backed_up = Vec::new();
    let mut installed = Vec::new();
    let transaction = (|| -> Result<()> {
        for product in PRODUCT_MODULES {
            let current = module_root.join(product.id);
            if fs::symlink_metadata(&current).is_ok() {
                let metadata = fs::symlink_metadata(&current)?;
                ensure!(
                    metadata.is_dir() && !metadata.file_type().is_symlink(),
                    "Unsafe installed product module {}",
                    product.id
                );
                fs::rename(&current, backup_root.join(product.id))?;
                backed_up.push(product.id);
            } else {
                fs::write(backup_root.join(format!("{}.absent", product.id)), b"")?;
            }
        }
        for retired in RETIRED_PRODUCT_MODULES {
            let current = module_root.join(retired);
            if fs::symlink_metadata(&current).is_ok() {
                let metadata = fs::symlink_metadata(&current)?;
                ensure!(
                    metadata.is_dir() && !metadata.file_type().is_symlink(),
                    "Unsafe retired product module {}",
                    retired
                );
                fs::rename(&current, backup_root.join(retired))?;
                backed_up.push(retired);
            }
        }
        for product in PRODUCT_MODULES {
            fs::rename(staging_root.join(product.id), module_root.join(product.id))?;
            installed.push(product.id);
            let _ = fs::remove_file(module_root.join(product.id).join("disable"));
            let _ = fs::remove_file(module_root.join(product.id).join("remove"));
        }
        verify_product_modules()?;
        File::create(NEED_REBOOT_PATH)?;
        Ok(())
    })();
    if let Err(error) = transaction {
        stop_profile_service();
        for id in installed.into_iter().rev() {
            let _ = remove_path_no_follow(&module_root.join(id));
        }
        for id in backed_up.into_iter().rev() {
            let backup = backup_root.join(id);
            if backup.exists() {
                let _ = fs::rename(backup, module_root.join(id));
            }
        }
        for product in PRODUCT_MODULES {
            if backup_root.join(format!("{}.absent", product.id)).exists() {
                let _ = remove_path_no_follow(&module_root.join(product.id));
            }
        }
        let _ = remove_path_no_follow(staging_root);
        let _ = remove_path_no_follow(backup_root);
        let _ = start_product_services();
        return Err(error);
    }
    remove_path_no_follow(staging_root)?;
    remove_path_no_follow(backup_root)?;
    Ok(())
}

fn recover_interrupted_profile_data_transaction() -> Result<()> {
    let backup_root = Path::new(PROFILE_DATA_BACKUP_ROOT);
    let metadata = match fs::symlink_metadata(backup_root) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        metadata.is_dir()
            && !metadata.file_type().is_symlink()
            && metadata.uid() == 0
            && metadata.gid() == 0,
        "Unsafe AndroidPatch profile-data backup"
    );
    let current = Path::new(defs::MODULE_DIR).join("device_profile_hook");
    for name in ["profiles", "profile.index"] {
        let backup = backup_root.join(name);
        if fs::symlink_metadata(&backup).is_ok() {
            remove_path_no_follow(&current.join(name))?;
            fs::rename(backup, current.join(name))?;
        }
    }
    remove_path_no_follow(backup_root)
}

fn verify_installed_profile_data(module_root: &Path) -> Result<()> {
    let verifier = module_root.join("bin/ksh-device-profile-data");
    ensure!(
        verifier.is_file(),
        "device-profile data verifier is missing"
    );
    let status = Command::new(&verifier)
        .arg("verify-all")
        .env("KSH_DEVICE_PROFILE_ROOT", module_root)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .context("Cannot verify installed device-profile data")?;
    ensure!(
        status.success(),
        "installed device-profile data failed verification"
    );
    Ok(())
}

fn install_profile_data_only() -> Result<()> {
    recover_interrupted_profile_data_transaction()?;
    verify_product_service_receipts()?;

    let product = PRODUCT_MODULES
        .iter()
        .find(|module| module.id == "device_profile_hook")
        .context("Built-in device-profile module is missing")?;
    let current = Path::new(defs::MODULE_DIR).join(product.id);
    let current_metadata = fs::symlink_metadata(&current)?;
    ensure!(
        current_metadata.is_dir()
            && !current_metadata.file_type().is_symlink()
            && current_metadata.uid() == 0
            && current_metadata.gid() == 0,
        "Unsafe installed device-profile module"
    );

    let staging_root = Path::new(PROFILE_DATA_STAGING_ROOT);
    let backup_root = Path::new(PROFILE_DATA_BACKUP_ROOT);
    remove_path_no_follow(staging_root)?;
    remove_path_no_follow(backup_root)?;
    fs::create_dir_all(staging_root)?;
    fs::create_dir_all(backup_root)?;
    fs::set_permissions(staging_root, Permissions::from_mode(0o700))?;
    fs::set_permissions(backup_root, Permissions::from_mode(0o700))?;
    let staged_module = staging_root.join(product.id);
    extract_product_module(product, &staged_module)?;
    verify_product_module_at(product, &staged_module)?;

    stop_profile_service();
    let transaction = (|| -> Result<()> {
        for name in ["profiles", "profile.index"] {
            let installed = current.join(name);
            let metadata = fs::symlink_metadata(&installed)?;
            ensure!(
                !metadata.file_type().is_symlink() && metadata.uid() == 0 && metadata.gid() == 0,
                "Unsafe installed profile-data object {name}"
            );
            fs::rename(&installed, backup_root.join(name))?;
            fs::rename(staged_module.join(name), &installed)?;
        }
        restorecon::restore_syscon(&current.join("profiles"))?;
        restorecon::restore_syscon(&current.join("profile.index"))?;
        verify_installed_profile_data(&current)?;
        start_product_services()?;
        Ok(())
    })();
    if let Err(error) = transaction {
        stop_profile_service();
        for name in ["profiles", "profile.index"] {
            let installed = current.join(name);
            let backup = backup_root.join(name);
            let _ = remove_path_no_follow(&installed);
            if fs::symlink_metadata(&backup).is_ok() {
                let _ = fs::rename(&backup, &installed);
            }
        }
        let _ = remove_path_no_follow(staging_root);
        let _ = remove_path_no_follow(backup_root);
        let _ = start_product_services();
        return Err(error);
    }
    remove_path_no_follow(staging_root)?;
    remove_path_no_follow(backup_root)?;
    println!("profile-data=installed");
    Ok(())
}

fn recover_interrupted_profile_module_transaction() -> Result<()> {
    let backup_root = Path::new(PROFILE_MODULE_BACKUP_ROOT);
    let metadata = match fs::symlink_metadata(backup_root) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    ensure!(
        metadata.is_dir()
            && !metadata.file_type().is_symlink()
            && metadata.uid() == 0
            && metadata.gid() == 0,
        "Unsafe AndroidPatch profile-module backup"
    );
    let current = Path::new(defs::MODULE_DIR).join("device_profile_hook");
    let backup = backup_root.join("device_profile_hook");
    if fs::symlink_metadata(&backup).is_ok() {
        remove_path_no_follow(&current)?;
        fs::rename(backup, current)?;
    }
    remove_path_no_follow(backup_root)
}

fn install_profile_module_live_only() -> Result<()> {
    recover_interrupted_profile_module_transaction()?;
    verify_product_service_receipts()?;
    let product = PRODUCT_MODULES
        .iter()
        .find(|module| module.id == "device_profile_hook")
        .context("Built-in device-profile module is missing")?;
    let current = Path::new(defs::MODULE_DIR).join(product.id);
    let current_metadata = fs::symlink_metadata(&current)?;
    ensure!(
        current_metadata.is_dir()
            && !current_metadata.file_type().is_symlink()
            && current_metadata.uid() == 0
            && current_metadata.gid() == 0,
        "Unsafe installed device-profile module"
    );

    let staging_root = Path::new(PROFILE_MODULE_STAGING_ROOT);
    let backup_root = Path::new(PROFILE_MODULE_BACKUP_ROOT);
    remove_path_no_follow(staging_root)?;
    remove_path_no_follow(backup_root)?;
    fs::create_dir_all(staging_root)?;
    fs::create_dir_all(backup_root)?;
    fs::set_permissions(staging_root, Permissions::from_mode(0o700))?;
    fs::set_permissions(backup_root, Permissions::from_mode(0o700))?;
    let staged = staging_root.join(product.id);
    extract_product_module(product, &staged)?;
    verify_product_module_at(product, &staged)?;
    preserve_profile_state(staging_root)?;
    let mut state_budget = 4096;
    protect_profile_state(&staged.join("ksh/state"), &mut state_budget)?;

    // KPMs are resident for this boot. A live controller/data update must keep
    // the exact installed KPM bytes, digest and authorization token.
    remove_path_no_follow(&staged.join("kpm"))?;
    let mut kpm_budget = 64;
    copy_root_owned_tree(&current.join("kpm"), &staged.join("kpm"), &mut kpm_budget)?;
    restorecon::restore_syscon(&staged)?;

    stop_profile_service();
    fs::rename(&current, backup_root.join(product.id))?;
    let transaction = (|| -> Result<()> {
        fs::rename(&staged, &current)?;
        verify_installed_profile_data(&current)?;
        start_product_services()?;
        Ok(())
    })();
    if let Err(error) = transaction {
        stop_profile_service();
        let _ = remove_path_no_follow(&current);
        let backup = backup_root.join(product.id);
        if fs::symlink_metadata(&backup).is_ok() {
            let _ = fs::rename(&backup, &current);
        }
        let _ = remove_path_no_follow(staging_root);
        let _ = remove_path_no_follow(backup_root);
        let _ = start_product_services();
        return Err(error);
    }
    remove_path_no_follow(staging_root)?;
    remove_path_no_follow(backup_root)?;
    println!("profile-module-live=installed");
    Ok(())
}

fn ensure_state_files() -> Result<()> {
    utils::ensure_file_exists(PACKAGE_CONFIG_PATH)?;
    utils::ensure_file_exists(SU_PATH_PATH)?;
    if fs::metadata(SU_PATH_PATH)?.len() == 0 {
        fs::write(SU_PATH_PATH, format!("{LEGACY_SU_PATH}\n"))?;
    }
    Ok(())
}

fn write_version() -> Result<()> {
    let mut version = File::create(VERSION_PATH)?;
    writeln!(version, "{}", defs::VERSION_CODE.trim())?;
    Ok(())
}

fn label_androidpatch_files() -> Result<()> {
    for path in [
        defs::WORKING_DIR,
        defs::BINARY_DIR,
        defs::APATCH_LOG_FOLDER,
        defs::DAEMON_PATH,
        APD_LINK_PATH,
        BUSYBOX_PATH,
        KPTOOLS_PATH,
        crate::assets::RESETPROP_PATH,
        crate::assets::MAGISKPOLICY_PATH,
        PACKAGE_CONFIG_PATH,
        SU_PATH_PATH,
    ] {
        restorecon::lsetfilecon(path, restorecon::ADB_CON)?;
    }
    Ok(())
}

pub fn install(source_dir: Option<&Path>) -> Result<()> {
    let apd_source = sibling_binary("libapd.so", source_dir)?;
    let busybox_source = sibling_binary("libbusybox.so", source_dir)?;
    let kptools_source = sibling_binary("libkptools.so", source_dir)?;

    utils::ensure_dir_exists(defs::WORKING_DIR)?;
    utils::ensure_dir_exists(defs::BINARY_DIR)?;
    utils::ensure_dir_exists(defs::APATCH_LOG_FOLDER)?;
    fs::set_permissions(defs::WORKING_DIR, Permissions::from_mode(0o755))?;
    fs::set_permissions(defs::BINARY_DIR, Permissions::from_mode(0o755))?;
    fs::set_permissions(defs::APATCH_LOG_FOLDER, Permissions::from_mode(0o700))?;

    copy_binary(&apd_source, Path::new(defs::DAEMON_PATH))?;
    copy_binary(&busybox_source, Path::new(BUSYBOX_PATH))?;
    copy_binary(&kptools_source, Path::new(KPTOOLS_PATH))?;
    replace_symlink(defs::DAEMON_PATH, APD_LINK_PATH)?;
    crate::assets::ensure_binaries()?;
    ensure_state_files()?;

    label_androidpatch_files()?;
    sepolicy::apply_magisk_policy_live()?;
    write_version()?;
    restorecon::lsetfilecon(VERSION_PATH, restorecon::ADB_CON)?;

    println!(
        "androidpatch=installed version={}",
        defs::VERSION_CODE.trim()
    );
    Ok(())
}

pub fn install_modules() -> Result<()> {
    install_product_modules()
}

pub fn install_profile_data() -> Result<()> {
    install_profile_data_only()
}

pub fn install_profile_module_live() -> Result<()> {
    install_profile_module_live_only()
}

pub fn uninstall() -> Result<()> {
    stop_profile_service();
    for module in PRODUCT_MODULES {
        remove_path_no_follow(&Path::new(defs::MODULE_DIR).join(module.id))?;
    }
    let _ = fs::remove_file(defs::DAEMON_PATH);
    let _ = fs::remove_file(LEGACY_KPATCH_PATH);
    let _ = fs::remove_dir_all(defs::BINARY_DIR);
    let _ = fs::remove_dir_all(defs::APATCH_LOG_FOLDER);
    let _ = fs::remove_file(VERSION_PATH);
    println!("androidpatch=uninstalled");
    Ok(())
}

pub fn status() -> Result<()> {
    let version = fs::read_to_string(VERSION_PATH).unwrap_or_default();
    let version = version.trim();
    if Path::new(defs::DAEMON_PATH).is_file() && !version.is_empty() {
        println!("androidpatch=installed version={version}");
    } else {
        println!("androidpatch=not-installed version=0");
    }
    Ok(())
}
