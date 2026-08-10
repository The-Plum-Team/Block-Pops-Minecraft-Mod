"use strict";

const node = (tag, className, text) => {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
};

const option = (select, value, label) => {
  const item = document.createElement("option");
  item.value = value;
  item.textContent = label;
  select.append(item);
};

const unique = (values) => [...new Set(values)].sort((a, b) => a.localeCompare(b));

function render(data) {
  document.querySelector("#project-name").textContent = data.project.name;
  document.querySelector("#project-description").textContent = data.project.description;
  document.querySelector("#source-link").href = data.project.repository;
  document.querySelector("#issues-link").href = data.project.issues;
  const branches = document.querySelector("#branch-filter");
  const loaders = document.querySelector("#loader-filter");
  const captures = document.querySelector("#capture-filter");
  unique(data.frames.map((frame) => frame.branch)).forEach((value) => option(branches, value, value));
  unique(data.frames.map((frame) => frame.loader)).forEach((value) => option(loaders, value, value));
  unique(data.frames.map((frame) => frame.capture_id)).forEach((value) => option(captures, value, value));

  const refresh = () => {
    const selected = data.frames.filter((frame) =>
      (branches.value === "all" || frame.branch === branches.value) &&
      (loaders.value === "all" || frame.loader === loaders.value) &&
      (captures.value === "all" || frame.capture_id === captures.value)
    );
    const gallery = document.querySelector("#gallery");
    gallery.replaceChildren();
    for (const frame of selected) {
      const figure = document.createElement("figure");
      const image = document.createElement("img");
      image.src = frame.image;
      image.width = frame.width;
      image.height = frame.height;
      image.loading = "lazy";
      image.alt = `${frame.title}, Minecraft ${frame.minecraft} on ${frame.loader}. ${frame.expectation}`;
      const caption = document.createElement("figcaption");
      caption.append(
        node("h3", "", frame.title),
        node("p", "", frame.expectation),
        node("p", "meta", `${frame.branch} @ ${frame.commit.slice(0, 12)} · ${frame.artifact_node} · ${frame.capture_id}`),
        node("p", "meta", `source ${frame.source_sha256.slice(0, 12)} · webp ${frame.published_sha256.slice(0, 12)}`)
      );
      figure.append(image, caption);
      gallery.append(figure);
    }
    document.querySelector("#status").textContent = `${selected.length} validated capture${selected.length === 1 ? "" : "s"}`;
  };
  [branches, loaders, captures].forEach((select) => select.addEventListener("change", refresh));
  refresh();
}

fetch("gallery-data.json", { credentials: "same-origin" })
  .then((response) => {
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  })
  .then((data) => {
    if (data.schema_version !== 1 || !Array.isArray(data.releases) || !Array.isArray(data.frames) || !data.frames.length) {
      throw new Error("unsupported or empty gallery inventory");
    }
    render(data);
  })
  .catch((error) => {
    const status = document.querySelector("#status");
    status.textContent = `Evidence gallery unavailable: ${error.message}`;
  });
