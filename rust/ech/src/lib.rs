//! JNI bindings for the ECH transport.
//!
//! Kotlin side: `ceui.pixiv.net.ech.EchClient` in the :net module.
//!
//! Methods:
//! - `nativeInit(): Boolean` — warm up the ECH client (fetch ECH config).
//! - `nativeRequest(method, url, headers: Array<String> "name\0value", body):
//!   String` — JSON `{"status":..,"headers":[[name,value]..],"body":"<b64>"}`
//!   or `{"error":"..."}`.

mod ech;

use base64::Engine;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jstring};
use jni::JNIEnv;

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s)
        .map(|x| x.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn get_str(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map_err(|e| format!("JNI get_string: {e}"))?
        .to_str()
        .map(|s| s.to_owned())
        .map_err(|e| format!("JNI string utf8: {e}"))
}

#[no_mangle]
pub extern "system" fn Java_ceui_pixiv_net_ech_EchClient_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match ech::ensure_client() {
        Ok(_) => 1,
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_ceui_pixiv_net_ech_EchClient_nativeRequest(
    mut env: JNIEnv,
    _class: JClass,
    method: JString,
    url: JString,
    headers: JObject,
    body: jbyteArray,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let method = get_str(&mut env, &method)?;
        let url = get_str(&mut env, &url)?;
        let mut pairs: Vec<(String, String)> = Vec::new();
        let arr = JObjectArray::from(headers);
        let len = env
            .get_array_length(&arr)
            .map_err(|e| format!("JNI array length: {e}"))?;
        for i in 0..len {
            let el = env
                .get_object_array_element(&arr, i)
                .map_err(|e| format!("JNI array element: {e}"))?;
            let s = get_str(&mut env, &JString::from(el))?;
            if let Some((name, value)) = s.split_once('\u{1}') {
                pairs.push((name.to_string(), value.to_string()));
            }
        }
        let body_bytes = if body.is_null() {
            None
        } else {
            let jarr = unsafe { JByteArray::from_raw(body) };
            Some(
                env.convert_byte_array(&jarr)
                    .map_err(|e| format!("JNI byte array: {e}"))?,
            )
        };
        let resp = ech::request(&method, &url, &pairs, body_bytes)?;
        let body_b64 = base64::engine::general_purpose::STANDARD.encode(&resp.body);
        let headers_json = serde_json::to_string(
            &resp
                .headers
                .iter()
                .map(|(k, v)| vec![k.clone(), v.clone()])
                .collect::<Vec<_>>(),
        )
        .unwrap_or_else(|_| "[]".into());
        Ok(format!(
            "{{\"status\":{},\"headers\":{},\"body\":\"{}\"}}",
            resp.status, headers_json, body_b64
        ))
    })();

    match result {
        Ok(json) => to_jstring(&mut env, &json),
        Err(err) => to_jstring(&mut env, &format!("{{\"error\":\"{}\"}}", escape_json(&err))),
    }
}

fn escape_json(s: &str) -> String {
    s.replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('\n', "\\n")
}
