# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy==2.2.6", "trimesh==4.8.3", "pillow==11.3.0"]
# ///
"""Convert a local RoboMaster App 1.1.5 resources export to display GLBs.

Original meshes and textures come from the official App; see README.md for provenance.
"""

from __future__ import annotations

import argparse
import json
import re
import struct
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import numpy as np
import trimesh
from numpy.typing import NDArray
from PIL import Image
from trimesh.visual.material import PBRMaterial
from trimesh.visual.texture import TextureVisuals

__all__: list[str] = []

OUTPUT = Path(__file__).resolve().parents[2] / "shared/src/commonMain/composeResources/files/models"


class AppMaterials:
    """Decode the extracted Unity texture channels into glTF's metallic/roughness workflow."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.cache: dict[int, PBRMaterial] = {}

    def texture(self, identifier: int) -> Image.Image | None:
        if identifier == 0:
            return None
        (path,) = (self.root / "Texture2D").glob(f"resources_{identifier}_*.png")
        image = Image.open(path).convert("RGBA")
        image.thumbnail((1024, 1024))
        return image

    def material(self, identifier: int) -> PBRMaterial:
        if identifier in self.cache:
            return self.cache[identifier]
        (path,) = (self.root / "Material").glob(f"resources_{identifier}_*.json")
        record = json.loads(path.read_text())
        properties = record["m_SavedProperties"]
        color = dict(properties["m_Colors"]).get("_Color", dict(r=1.0, g=1.0, b=1.0, a=1.0))
        transparent = dict(properties["m_Floats"]).get("_Mode", 0) in (2, 3)
        slots = {k: v["m_Texture"]["m_PathID"] for k, v in properties["m_TexEnvs"]}
        albedo = self.texture(slots.get("_MainTex", 0))
        # Unity's DXT5nm stores X in alpha, Y in green. This is a numeric format conversion,
        # not an artistic alteration: reconstruct positive Z before writing glTF's RGB normal.
        packed_normal = self.texture(slots.get("_BumpMap", 0))
        normal = None
        if packed_normal is not None:
            pixels = np.asarray(packed_normal, dtype=np.float32) / 255
            x = pixels[:, :, 3] * 2 - 1
            y = pixels[:, :, 1] * 2 - 1
            z = np.sqrt(np.maximum(0, 1 - x * x - y * y))
            normal = Image.fromarray(
                np.rint((np.stack([x, y, z], axis=2) + 1) * 127.5).astype(np.uint8)
            )
        metallic = self.texture(slots.get("_MetallicGlossMap", 0))
        roughness = self.texture(slots.get("_SpecGlossMap", 0))
        size = albedo.size if albedo else (1, 1)
        # The original records bind named *_Roughness / *_R images as specular maps. These
        # are roughness data, not alpha smoothness; glTF packs roughness G and metallic B.
        mr = Image.merge(
            "RGB",
            (
                Image.new("L", size, 255),
                roughness.resize(size).getchannel("R") if roughness else Image.new("L", size, 170),
                metallic.resize(size).getchannel("R") if metallic else Image.new("L", size, 0),
            ),
        )
        result = PBRMaterial(
            name=record["m_Name"],
            baseColorFactor=[color[c] for c in ("r", "g", "b", "a")],
            baseColorTexture=albedo,
            normalTexture=normal,
            metallicRoughnessTexture=mr,
            metallicFactor=1.0,
            roughnessFactor=1.0
            if roughness
            else 1.0 - dict(properties["m_Floats"]).get("_Glossiness", 0.0),
            emissiveFactor=[
                min(1.0, dict(properties["m_Colors"]).get("_EmissionColor", {}).get(c, 0.0))
                if "_EMISSION" in record["m_ShaderKeywords"]
                else 0.0
                for c in ("r", "g", "b")
            ],
            occlusionTexture=self.texture(slots.get("_OcclusionMap", 0)),
            alphaMode="BLEND" if transparent else "OPAQUE",
        )
        if identifier == 38:
            # Smoked polycarbonate, matched against DJI's photographed replacement shells.
            # The App's 0.894 alpha is a stylized display choice, not measured transmission.
            result.baseColorFactor = [1.0, 1.0, 1.0, 1.0]
            result.metallicFactor = 0.0
            result.roughnessFactor = 0.75
        if identifier == 46:
            result.baseColorFactor = [0.012, 0.012, 0.012, 1.0]
        if identifier == 71:
            result.roughnessFactor = 0.72
            result.metallicFactor = 0.0
        self.cache[identifier] = result
        return result


class AppModel:
    """Original Unity meshes, submesh bindings and local transforms from App 1.1.5.

    The S1 source prefab has editor placeholder materials. The textured EP prefab
    supplies the identical chassis mesh IDs; LAB 3 supplies S1 turret bindings.
    IDs are scoped to the fixed resources export, never inferred from filenames.
    """

    def __init__(self, root: Path, materials: AppMaterials):
        self.root = root
        self.materials = materials
        self.objects = self.records("GameObject")
        self.transforms = self.records("Transform")
        self.renderers = {
            r["m_GameObject"]["m_PathID"]: r for r in self.records("MeshRenderer").values()
        }
        self.filters = {
            r["m_GameObject"]["m_PathID"]: r["m_Mesh"]["m_PathID"]
            for r in self.records("MeshFilter").values()
        }
        self.mesh_paths = {
            int(p.name.split("_")[1]): p for p in (root / "Mesh").glob("resources_*.obj")
        }
        self.mesh_materials: dict[int, list[int]] = {}
        self.mesh_cache: dict[int, list[trimesh.Trimesh]] = {}
        for tid in self.descendants(20795):
            gid = self.transforms[tid]["m_GameObject"]["m_PathID"]
            if gid in self.filters and gid in self.renderers:
                self.mesh_materials[self.filters[gid]] = [
                    p["m_PathID"] for p in self.renderers[gid]["m_Materials"]
                ]

    def records(self, kind: str) -> dict[int, Any]:
        return {
            int(p.name.split("_")[1]): json.loads(p.read_text())
            for p in (self.root / kind).glob("resources_*.json")
        }

    def descendants(self, tid: int) -> Iterator[int]:
        yield tid
        for child in self.transforms[tid]["m_Children"]:
            yield from self.descendants(child["m_PathID"])

    @staticmethod
    def matrix(record: dict[str, Any]) -> NDArray[np.float64]:
        q = record["m_LocalRotation"]
        result = trimesh.transformations.quaternion_matrix([q[k] for k in ("w", "x", "y", "z")])
        result[:3, :3] = result[:3, :3] @ np.diag(
            [record["m_LocalScale"][k] for k in ("x", "y", "z")]
        )
        result[:3, 3] = [record["m_LocalPosition"][k] for k in ("x", "y", "z")]
        return result

    def mesh_parts(self, mid: int) -> list[trimesh.Trimesh]:
        if mid in self.mesh_cache:
            return self.mesh_cache[mid]
        vertices: list[list[float]] = []
        normals: list[list[float]] = []
        uv: list[list[float]] = []
        groups: list[list[list[int]]] = []
        faces: list[list[int]] = []
        for line in self.mesh_paths[mid].read_text().splitlines():
            fields = line.split()
            if not fields:
                continue
            if fields[0] == "v":
                vertices.append([float(v) for v in fields[1:4]])
            elif fields[0] == "vn":
                normals.append([float(v) for v in fields[1:4]])
            elif fields[0] == "vt":
                uv.append([float(v) for v in fields[1:3]])
            elif fields[0] == "g" and faces:
                groups.append(faces)
                faces = []
            elif fields[0] == "f":
                # UnityPy exports triangles with the same position/normal/UV indices.
                if len(fields) != 4 or any(len(set(v.split("/"))) != 1 for v in fields[1:]):
                    raise ValueError(f"Unexpected UnityPy OBJ indexing: {mid}")
                faces.append([int(v.split("/")[0]) - 1 for v in fields[1:]])
        if faces:
            groups.append(faces)
        parts = []
        for group in groups:
            used, indices = np.unique(np.asarray(group), return_inverse=True)
            points = np.asarray(vertices, dtype=np.float64)[used]
            triangles = indices.reshape((-1, 3))
            n = np.asarray(normals, dtype=np.float64)[used]
            face_normals = np.cross(
                points[triangles[:, 1]] - points[triangles[:, 0]],
                points[triangles[:, 2]] - points[triangles[:, 0]],
            )
            valid = np.linalg.norm(face_normals, axis=1) > 1e-12
            triangles, face_normals = triangles[valid], face_normals[valid]
            active, compact = np.unique(triangles, return_inverse=True)
            triangles = compact.reshape((-1, 3))
            points, n, used = points[active], n[active], used[active]
            invalid_normals = np.linalg.norm(n, axis=1) < 1e-8
            sums = np.zeros_like(n)
            for corner in range(3):
                np.add.at(sums, triangles[:, corner], face_normals)
            n[invalid_normals] = sums[invalid_normals]
            lengths = np.linalg.norm(n, axis=1)
            # Opposite faces can cancel the averaged normal at a seam. Use an
            # incident non-degenerate face there rather than exporting a zero vector.
            for vertex in np.flatnonzero(lengths == 0):
                incident = np.flatnonzero(np.any(triangles == vertex, axis=1))
                n[vertex] = face_normals[incident[0]]
            lengths = np.linalg.norm(n, axis=1)
            n /= lengths[:, None]
            mesh = trimesh.Trimesh(
                vertices=points, faces=triangles, vertex_normals=n, process=False
            )
            mesh.visual = TextureVisuals(uv=np.asarray(uv)[used] if uv else None)
            parts.append(mesh)
        self.mesh_cache[mid] = parts
        return parts

    def convert_workshop(self) -> None:
        """Restore the exported LAB 3 static environment around its robot anchor.

        Light shafts / baked shadow billboards belong to Unity's lighting pipeline and
        are excluded; Filament supplies actual illumination and contact shadows.
        """
        scene = trimesh.Scene()
        basis = np.diag([3.0, 3.0, -3.0, 1.0])
        anchor = np.linalg.inv(self.matrix(self.transforms[20576]))
        obj_basis = np.diag([-1.0, 1.0, 1.0, 1.0])
        batches: dict[int, list[trimesh.Trimesh]] = {}
        # Reuse source props in a near display bay: the original warehouse fixtures
        # are tens of metres away and outside the home camera's field of view.
        display_sources: dict[int, list[tuple[int, trimesh.Trimesh]]] = {}
        missing: list[str] = []
        recovered = 0
        # Static batching can clear a scene MeshFilter. Recover only unambiguous
        # authored prefab bindings with the exact object name, never fuzzy matches.
        prefab_meshes: dict[str, set[int]] = {}
        for gid, mid in self.filters.items():
            if mid in self.mesh_paths:
                name = re.sub(r" \(\d+\)$", "", self.objects[gid]["m_Name"])
                prefab_meshes.setdefault(name, set()).add(mid)

        def walk(tid: int, parent: NDArray[np.float64]) -> None:
            nonlocal recovered
            record = self.transforms[tid]
            gid = record["m_GameObject"]["m_PathID"]
            obj = self.objects[gid]
            if not obj["m_IsActive"] or obj["m_Name"].startswith("UCX_"):
                return
            current = parent @ self.matrix(record)
            renderer = self.renderers.get(gid)
            mid = self.filters.get(gid)
            if renderer and renderer["m_Enabled"]:
                ids = [p["m_PathID"] for p in renderer["m_Materials"]]
                if all(i not in (0, 5, 6, 7, 9, 55) for i in ids) and mid not in self.mesh_paths:
                    candidates = prefab_meshes.get(re.sub(r" \(\d+\)$", "", obj["m_Name"]), set())
                    if len(candidates) == 1:
                        mid = next(iter(candidates))
                        recovered += 1
                if any(i in (0, 5, 6, 7, 9, 55) for i in ids):
                    pass
                elif mid not in self.mesh_paths:
                    missing.append(f"{tid}:{obj['m_Name']}")
                elif all(i not in (0, 5, 6, 7, 9, 55) for i in ids):
                    parts = self.mesh_parts(mid)
                    if len(parts) != len(ids):
                        raise ValueError(f"Workshop submesh mismatch: {tid}")
                    for source, identifier in zip(parts, ids, strict=True):
                        mesh = source.copy()
                        mesh.apply_transform(basis @ anchor @ current @ obj_basis)
                        assert isinstance(mesh.visual, TextureVisuals)
                        # Unity texture tiling/offset is independent of mesh UVs.
                        material_path = next(
                            (self.root / "Material").glob(f"resources_{identifier}_*.json")
                        )
                        props = json.loads(material_path.read_text())["m_SavedProperties"]
                        slot = dict(props["m_TexEnvs"]).get("_MainTex")
                        if slot and mesh.visual.uv is not None:
                            mesh.visual.uv = mesh.visual.uv * [
                                slot["m_Scale"][c] for c in ("x", "y")
                            ] + [slot["m_Offset"][c] for c in ("x", "y")]
                        mesh.visual.material = self.materials.material(identifier)
                        batches.setdefault(identifier, []).append(mesh)
                        if tid in (19537, 20345, 19971):
                            display_sources.setdefault(tid, []).append((identifier, mesh))
            for child in record["m_Children"]:
                walk(child["m_PathID"], current)

        walk(20308, np.eye(4))
        # Authored presentation layout, not an assertion of the original Unity placement.
        for tid, scale, position in (
            (19537, 0.38, (-0.8, 0.0, -3.8)),
            (19971, 0.25, (-2.3, 0.0, -1.5)),
            (20345, 0.45, (-2.5, 0.16, -1.4)),
            (20345, 0.32, (-2.2, 0.83, -1.5)),
            (20345, 0.38, (-1.6, 0.0, -2.1)),
        ):
            parts = display_sources[tid]
            bounds = np.array([mesh.bounds for _, mesh in parts])
            low, high = bounds[:, 0].min(axis=0), bounds[:, 1].max(axis=0)
            origin = np.array([(low[0] + high[0]) / 2, low[1], (low[2] + high[2]) / 2])
            for identifier, source in parts:
                mesh = source.copy()
                mesh.apply_translation(-origin)
                mesh.apply_scale(scale)
                mesh.apply_translation(position)
                batches[identifier].append(mesh)
        for identifier, meshes in batches.items():
            merged = trimesh.util.concatenate(meshes)
            normals = np.array(merged.vertex_normals, copy=True)
            lengths = np.linalg.norm(normals, axis=1)
            for vertex in np.flatnonzero(lengths < 1e-8):
                incident = np.flatnonzero(np.any(merged.faces == vertex, axis=1))
                normals[vertex] = merged.face_normals[incident[0]]
            normals /= np.linalg.norm(normals, axis=1)[:, None]
            merged.vertex_normals = normals
            scene.add_geometry(merged, node_name=f"workshop-{identifier}")
        data = scene.export(file_type="glb", include_normals=True)
        assert isinstance(data, bytes)
        (OUTPUT / "workshop.glb").write_bytes(data)
        print(
            "workshop",
            len(data),
            "bytes",
            sum(map(len, batches.values())),
            "parts",
            "recovered",
            recovered,
            "missing",
            missing,
            "bounds",
            scene.bounds.tolist(),
        )

    def convert(self, variant: str) -> None:
        scene = trimesh.Scene()
        # UnityPy OBJ already mirrors X; transform records still use Unity coordinates.
        obj_basis = np.diag([-1.0, 1.0, 1.0, 1.0])
        world_basis = np.diag([3.0, 3.0, -3.0, 1.0])
        yaw_matrix = self.matrix(self.transforms[18351])
        pitch_matrix = yaw_matrix @ self.matrix(self.transforms[18355])
        pivots = {
            "base": np.zeros(3),
            "yaw": (world_basis @ yaw_matrix)[:3, 3],
            "pitch": (world_basis @ pitch_matrix)[:3, 3],
        }
        scene.graph.update(frame_to="base", matrix=np.eye(4))
        if variant == "s1":
            yaw = np.eye(4)
            yaw[:3, 3] = pivots["yaw"]
            scene.graph.update(frame_to="yaw", frame_from="base", matrix=yaw)
            pitch = np.eye(4)
            pitch[:3, 3] = pivots["pitch"] - pivots["yaw"]
            scene.graph.update(frame_to="pitch", frame_from="yaw", matrix=pitch)

        def walk(tid: int, parent: NDArray[np.float64], group: str = "base") -> None:
            record = self.transforms[tid]
            gid = record["m_GameObject"]["m_PathID"]
            obj = self.objects[gid]
            name = obj["m_Name"]
            if not obj["m_IsActive"] or name.startswith(("UCX_", "Plane")):
                return
            if variant == "base" and tid == 18351:
                return
            if tid == 18351:
                group = "yaw"
            if tid == 18355:
                group = "pitch"
            current = parent @ self.matrix(record)
            wheels = (
                (20781, 20780, 20778, 20779) if variant == "ep" else (18366, 18365, 18367, 18368)
            )
            if tid in wheels:
                group = f"wheel{wheels.index(tid)}"
                pivots[group] = (world_basis @ current)[:3, 3].copy()
                pivot = np.eye(4)
                pivot[:3, 3] = pivots[group]
                scene.graph.update(frame_to=group, frame_from="base", matrix=pivot)
            mid = self.filters.get(gid)
            if mid:
                renderer = self.renderers[gid]
                if renderer["m_Enabled"]:
                    ids = self.mesh_materials.get(mid)
                    if ids is None:
                        # S1 gimbal bindings recovered from the textured LAB 3 hierarchy.
                        remap = {211: 50, 239: 49, 242: 41, 245: 46, 225: 44, 236: 36}
                        ids = [remap[p["m_PathID"]] for p in renderer["m_Materials"]]
                    if variant != "ep":
                        ids = [48 if i == 60 else i for i in ids]  # S1 uses black wheel hubs.
                    if tid == 18377:
                        ids = [46]  # Speaker cable: black insulation, not the silver TOP atlas.
                    if tid in (18104, 18154, 18201):
                        ids = [50]  # Gimbal base braces, not LEDs.
                    parts = self.mesh_parts(mid)
                    if len(parts) != len(ids):
                        raise ValueError(f"Submesh/material count mismatch: {mid}")
                    for index, source in enumerate(parts):
                        mesh = source.copy()
                        transform = world_basis @ current @ obj_basis
                        transform[:3, 3] -= pivots[group]
                        mesh.apply_transform(transform)
                        assert isinstance(mesh.visual, TextureVisuals)
                        mesh.visual.material = self.materials.material(ids[index])
                        scene.add_geometry(
                            mesh, node_name=f"{tid}-{index}-{name}", parent_node_name=group
                        )
            for child in record["m_Children"]:
                walk(child["m_PathID"], current, group)

        # Drop prefab world placement, keeping all authored child transforms.
        for child in self.transforms[20795 if variant == "ep" else 18392]["m_Children"]:
            walk(child["m_PathID"], np.eye(4))
        data = scene.export(file_type="glb", include_normals=True)
        assert isinstance(data, bytes)
        data = add_joint_animations(data)
        (OUTPUT / f"robot-{variant}.glb").write_bytes(data)
        print(variant, len(data), "bytes", scene.bounds.tolist())


def add_joint_animations(data: bytes) -> bytes:
    """Independent glTF channels let the renderer pose instances without name-manager support.

    yaw: -360..360 degrees over 0..8 seconds; pitch: -180..180 over 0..4.
    These are pose lookup curves, never auto-playing motions.
    """
    json_length = struct.unpack_from("<I", data, 12)[0]
    tree: dict[str, Any] = json.loads(data[20 : 20 + json_length])
    binary = bytearray(data[28 + json_length :])
    for material in tree["materials"]:
        transmission = {"Armor Module": 0.45, "SOMRTHING 1": 0.85}.get(material.get("name"))
        if transmission is not None:
            # Optical plastic transmission keeps surface reflection; alpha is coverage,
            # and produces depth artifacts on the nested shell/interior geometry.
            material["alphaMode"] = "OPAQUE"
            material["pbrMetallicRoughness"]["baseColorFactor"][3] = 1.0
            material["pbrMetallicRoughness"]["metallicFactor"] = 0.0
            material.setdefault("extensions", {})["KHR_materials_transmission"] = {
                "transmissionFactor": transmission
            }
            if material.get("name") == "Armor Module" and "normalTexture" in material:
                material["normalTexture"]["scale"] = 0.6
            if material.get("name") == "SOMRTHING 1":
                # The clear lid is an optical shell, not the opaque chassis atlas beneath it.
                material["pbrMetallicRoughness"].pop("baseColorTexture", None)
                material["pbrMetallicRoughness"]["baseColorFactor"] = [0.9, 0.95, 1.0, 1.0]
                material["pbrMetallicRoughness"]["roughnessFactor"] = 0.15
    tree.setdefault("extensionsUsed", []).append("KHR_materials_transmission")

    def accessor(values: list[float], kind: str, count: int) -> int:
        offset = len(binary)
        packed = struct.pack(f"<{len(values)}f", *values)
        binary.extend(packed)
        view = len(tree["bufferViews"])
        tree["bufferViews"].append({"buffer": 0, "byteOffset": offset, "byteLength": len(packed)})
        item: dict[str, Any] = {
            "bufferView": view,
            "componentType": 5126,
            "count": count,
            "type": kind,
        }
        if kind == "SCALAR":
            item.update(min=[min(values)], max=[max(values)])
        index = len(tree["accessors"])
        tree["accessors"].append(item)
        return index

    tree["animations"] = []
    joints = [("yaw", 360, 1), ("pitch", 180, 0)] + [(f"wheel{i}", 360, 0) for i in range(4)]
    for name, limit, axis in joints:
        if not any(node.get("name") == name for node in tree["nodes"]):
            continue
        wheel = name.startswith("wheel")
        angles = list(range(0 if wheel else -limit, limit + 1, 90))
        times = accessor([float(i) for i in range(len(angles))], "SCALAR", len(angles))
        quaternions: list[float] = []
        for angle in angles:
            half = float(angle) * (1 if wheel else -1) * np.pi / 360
            q = [0.0, 0.0, 0.0, float(np.cos(half))]
            q[axis] = float(np.sin(half))
            quaternions.extend(q)
        rotations = accessor(quaternions, "VEC4", len(angles))
        node = next(i for i, node in enumerate(tree["nodes"]) if node.get("name") == name)
        matrix = tree["nodes"][node].pop("matrix", None)
        if matrix is not None:
            tree["nodes"][node]["translation"] = matrix[12:15]
        tree["animations"].append(
            {
                "name": name,
                "samplers": [{"input": times, "output": rotations, "interpolation": "LINEAR"}],
                "channels": [{"sampler": 0, "target": {"node": node, "path": "rotation"}}],
            }
        )
    tree["buffers"][0]["byteLength"] = len(binary)
    encoded = json.dumps(tree, separators=(",", ":")).encode()
    encoded += b" " * ((-len(encoded)) % 4)
    total = 12 + 8 + len(encoded) + 8 + len(binary)
    return (
        struct.pack("<4sII", b"glTF", 2, total)
        + struct.pack("<I4s", len(encoded), b"JSON")
        + encoded
        + struct.pack("<I4s", len(binary), b"BIN\x00")
        + binary
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-assets", type=Path, required=True)
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    model = AppModel(args.app_assets, AppMaterials(args.app_assets))
    model.convert_workshop()
    for variant in ("base", "s1", "ep"):
        model.convert(variant)


if __name__ == "__main__":
    main()
