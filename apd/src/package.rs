use std::{
    fs::File,
    io::{self, BufRead, Write},
    path::Path,
    thread,
    time::Duration,
};
use log::{info, warn};
use serde::{Deserialize, Serialize};

const PACKAGE_CONFIG_PATH: &str = "/data/adb/ap/package_config";
const PACKAGE_CONFIG_HEADER: [&str; 6] =
    ["pkg", "exclude", "allow", "uid", "to_uid", "sctx"];

#[derive(Deserialize, Serialize, Clone)]
pub struct PackageConfig {
    pub pkg: String,
    pub exclude: i32,
    pub allow: i32,
    pub uid: i32,
    pub to_uid: i32,
    pub sctx: String,
}

pub fn read_ap_package_config() -> Vec<PackageConfig> {
    let max_retry = 5;
    for _ in 0..max_retry {
        let file = match File::open(PACKAGE_CONFIG_PATH) {
            Ok(file) => file,
            Err(e) => {
                warn!("Error opening file: {}", e);
                thread::sleep(Duration::from_secs(1));
                continue;
            }
        };

        let mut reader = csv::Reader::from_reader(file);
        let mut package_configs = Vec::new();
        let mut success = true;

        for record in reader.deserialize() {
            match record {
                Ok(config) => package_configs.push(config),
                Err(e) => {
                    warn!("Error deserializing record: {}", e);
                    success = false;
                    break;
                }
            }
        }

        if success {
            return package_configs;
        }
        thread::sleep(Duration::from_secs(1));
    }
    Vec::new()
}

fn write_package_config_stream<W: Write>(
    output: W,
    package_configs: &[PackageConfig],
) -> io::Result<()> {
    let mut writer = csv::Writer::from_writer(output);
    if package_configs.is_empty() {
        writer.write_record(PACKAGE_CONFIG_HEADER).map_err(|error| {
            io::Error::new(io::ErrorKind::Other, error)
        })?;
    } else {
        for config in package_configs {
            writer.serialize(config).map_err(|error| {
                io::Error::new(io::ErrorKind::Other, error)
            })?;
        }
    }
    writer.flush()
}

pub fn write_ap_package_config(package_configs: &[PackageConfig]) -> io::Result<()> {
    let max_retry = 5;
    for _ in 0..max_retry {
        let temp_path = "/data/adb/ap/package_config.tmp";
        let file = match File::create(temp_path) {
            Ok(file) => file,
            Err(e) => {
                warn!("Error creating temp file: {}", e);
                thread::sleep(Duration::from_secs(1));
                continue;
            }
        };

        if let Err(e) = write_package_config_stream(file, package_configs) {
            warn!("Error flushing writer: {}", e);
            thread::sleep(Duration::from_secs(1));
            continue;
        }

        if let Err(e) = std::fs::rename(temp_path, PACKAGE_CONFIG_PATH) {
            warn!("Error renaming temp file: {}", e);
            thread::sleep(Duration::from_secs(1));
            continue;
        }
        return Ok(());
    }
    Err(io::Error::new(
        io::ErrorKind::Other,
        "Failed after max retries",
    ))
}

fn read_lines<P>(filename: P) -> io::Result<io::Lines<io::BufReader<File>>>
where
    P: AsRef<Path>,
{
    File::open(filename).map(|file| io::BufReader::new(file).lines())
}

pub fn synchronize_package_uid() -> io::Result<()> {
    info!("[synchronize_package_uid] Start synchronizing root list with system packages...");

    let max_retry = 5;
    for _ in 0..max_retry {
        match read_lines("/data/system/packages.list") {
            Ok(lines) => {
                let lines: Vec<_> = lines.filter_map(|line| line.ok()).collect();

                let header_missing = std::fs::metadata(PACKAGE_CONFIG_PATH)
                    .map(|metadata| metadata.len() == 0)
                    .unwrap_or(true);

                let mut package_configs = read_ap_package_config();

                let system_packages: Vec<String> = lines
                    .iter()
                    .filter_map(|line| line.split_whitespace().next())
                    .map(|pkg| pkg.to_string())
                    .collect();

                let original_len = package_configs.len();
                package_configs.retain(|config| system_packages.contains(&config.pkg));
                let removed_count = original_len - package_configs.len();

                if removed_count > 0 {
                    info!(
                        "Removed {} uninstalled package configurations",
                        removed_count
                    );
                }

                let mut updated = false;

                for line in &lines {
                    let words: Vec<&str> = line.split_whitespace().collect();
                    if words.len() >= 2 {
                        let pkg_name = words[0];
                        if let Ok(uid) = words[1].parse::<i32>() {
                            for config in package_configs
                                .iter_mut()
                                .filter(|config| config.pkg == pkg_name)
                            {
                                if config.uid % 100000 != uid % 100000 {
                                    let new_uid = config.uid / 100000 * 100000 + uid % 100000;
                                    info!(
                                        "Updating uid for package {}: {} -> {}",
                                        pkg_name, config.uid, new_uid
                                    );
                                    config.uid = new_uid;
                                    updated = true;
                                }
                            }
                        } else {
                            warn!("Error parsing uid: {}", words[1]);
                        }
                    }
                }

                if header_missing || updated || removed_count > 0 {
                    write_ap_package_config(&package_configs)?;
                }
                return Ok(());
            }
            Err(e) => {
                warn!("Error reading packages.list: {}", e);
                thread::sleep(Duration::from_secs(1));
            }
        }
    }
    Err(io::Error::new(
        io::ErrorKind::Other,
        "Failed after max retries",
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_package_config_keeps_a_parseable_csv_header() {
        let mut output = Vec::new();
        write_package_config_stream(&mut output, &[]).unwrap();
        assert_eq!(
            String::from_utf8(output).unwrap(),
            "pkg,exclude,allow,uid,to_uid,sctx\n"
        );
    }

    #[test]
    fn populated_package_config_keeps_the_existing_schema() {
        let config = PackageConfig {
            pkg: "com.example.app".to_string(),
            exclude: 0,
            allow: 1,
            uid: 10123,
            to_uid: 0,
            sctx: "u:r:untrusted_app:s0".to_string(),
        };
        let mut output = Vec::new();
        write_package_config_stream(&mut output, &[config]).unwrap();
        let text = String::from_utf8(output).unwrap();
        assert!(text.starts_with("pkg,exclude,allow,uid,to_uid,sctx\n"));
        assert!(text.contains("com.example.app,0,1,10123,0,u:r:untrusted_app:s0\n"));
    }
}
