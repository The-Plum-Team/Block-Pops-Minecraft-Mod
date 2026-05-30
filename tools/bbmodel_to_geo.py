"""
Convert BBModel (Figura/CPM format) to Bedrock Geometry JSON (GeckoLib compatible).
Handles multi-texture models by splitting bones and generating extra_textures config.

Usage:
    python bbmodel_to_geo.py <bbmodel_path> <figure_id> <collection_id> [--output-dir DIR] [--include-hidden]

Example:
    python bbmodel_to_geo.py "Venus.bbmodel" venus willowmedia --output-dir ./output --include-hidden
"""
import json
import os
import sys
import hashlib
import shutil
import argparse


CPM_BONE_RENAMES = {
    'head': 'Head',
    'body': 'Body',
    'left_arm': 'LeftArm',
    'right_arm': 'RightArm',
    'left_leg': 'LeftLeg',
    'right_leg': 'RightLeg',
}


def get_elem_texture(el):
    """Get the texture index used by an element (first non-None texture found)."""
    for fd in el.get('faces', {}).values():
        t = fd.get('texture')
        if t is not None:
            return t
    return -1


def convert_mesh_to_cube(el, uv_scale=None):
    """Convert a simple box-like mesh (8 verts, 6 quad faces) to a Bedrock cube."""
    verts = el.get('vertices', {})
    faces = el.get('faces', {})

    if len(verts) != 8 or len(faces) != 6:
        return None

    # Mesh vertices are in local space — add element origin for world space
    mesh_origin = el.get('origin', [0, 0, 0])
    positions = [[v[0] + mesh_origin[0], v[1] + mesh_origin[1], v[2] + mesh_origin[2]]
                 for v in verts.values()]
    xs = [p[0] for p in positions]
    ys = [p[1] for p in positions]
    zs = [p[2] for p in positions]

    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    min_z, max_z = min(zs), max(zs)

    size = [max_x - min_x, max_y - min_y, max_z - min_z]
    origin = [-(min_x + size[0]), min_y, min_z]

    cube = {'origin': origin, 'size': size}

    # Determine which cube face each mesh face maps to by checking vertex positions
    face_map = {}  # 'north'/'south'/... -> face_data
    for fid, face_data in faces.items():
        vert_ids = face_data.get('vertices', [])
        if len(vert_ids) != 4:
            continue
        face_positions = [[verts[vid][i] + mesh_origin[i] for i in range(3)]
                          for vid in vert_ids if vid in verts]
        if len(face_positions) != 4:
            continue

        fxs = [p[0] for p in face_positions]
        fys = [p[1] for p in face_positions]
        fzs = [p[2] for p in face_positions]

        x_range = max(fxs) - min(fxs)
        y_range = max(fys) - min(fys)
        z_range = max(fzs) - min(fzs)

        # The face with smallest range on an axis is perpendicular to that axis
        min_range = min(x_range, y_range, z_range)
        avg_x = sum(fxs) / 4
        avg_y = sum(fys) / 4
        avg_z = sum(fzs) / 4
        mid_x = (min_x + max_x) / 2
        mid_y = (min_y + max_y) / 2
        mid_z = (min_z + max_z) / 2

        if min_range == y_range:
            face_map['up' if avg_y > mid_y else 'down'] = face_data
        elif min_range == z_range:
            face_map['south' if avg_z > mid_z else 'north'] = face_data
        elif min_range == x_range:
            face_map['east' if avg_x > mid_x else 'west'] = face_data

    # Extract UVs from mapped faces
    sx, sy = uv_scale if uv_scale else (1.0, 1.0)
    uv = {}
    for face_name, face_data in face_map.items():
        face_uvs = face_data.get('uv', {})
        if not face_uvs:
            continue
        uv_coords = list(face_uvs.values())
        if not uv_coords:
            continue
        us = [c[0] for c in uv_coords]
        vs = [c[1] for c in uv_coords]
        uv[face_name] = {
            'uv': [min(us) * sx, min(vs) * sy],
            'uv_size': [(max(us) - min(us)) * sx, (max(vs) - min(vs)) * sy]
        }

    cube['uv'] = uv
    return cube


def convert_element_to_cube(el, uv_scale=None):
    """Convert a BBModel element to a Bedrock geometry cube.
    uv_scale: (scale_x, scale_y) to rescale UVs when texture resolution differs from model resolution.
    """
    if el.get('type') == 'mesh':
        return convert_mesh_to_cube(el, uv_scale=uv_scale)

    from_pos = el.get('from', [0, 0, 0])
    to_pos = el.get('to', [0, 0, 0])

    size = [to_pos[i] - from_pos[i] for i in range(3)]
    origin = from_pos[:]
    origin[0] = -(origin[0] + size[0])

    cube = {'origin': origin, 'size': size}

    inflate = el.get('inflate')
    if inflate and inflate != 0:
        cube['inflate'] = inflate

    rotation = el.get('rotation')
    if rotation and rotation != [0, 0, 0]:
        cube['rotation'] = [-rotation[0], -rotation[1], rotation[2]]
        pivot = el.get('origin', [0, 0, 0])[:]
        pivot[0] *= -1
        cube['pivot'] = pivot

    faces = el.get('faces', {})
    box_uv = el.get('box_uv', False)

    if box_uv:
        cube['uv'] = el.get('uv_offset', [0, 0])
    else:
        sx, sy = uv_scale if uv_scale else (1.0, 1.0)
        uv = {}
        for face_name in ['north', 'east', 'south', 'west', 'up', 'down']:
            face = faces.get(face_name)
            if face and (face.get('texture') is not None or face.get('uv') is not None):
                face_uv = face.get('uv', [0, 0, 0, 0])
                uv[face_name] = {
                    'uv': [face_uv[0] * sx, face_uv[1] * sy],
                    'uv_size': [(face_uv[2] - face_uv[0]) * sx, (face_uv[3] - face_uv[1]) * sy]
                }
        cube['uv'] = uv

    if el.get('mirror_uv'):
        cube['mirror'] = True

    return cube


def convert_bbmodel_to_geo(bbmodel_path, figure_id, collection_id, include_hidden=False, exclude_bones=None, include_bones=None):
    """
    Convert a BBModel file to GeckoLib geo.json with multi-texture support.

    Returns: (geo_dict, texture_map) where texture_map is {tex_index: [bone_names]}
    """
    with open(bbmodel_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    resolution = data.get('resolution', {'width': 64, 'height': 64})
    elements = data.get('elements', [])
    outliner = data.get('outliner', [])
    groups = data.get('groups', [])
    textures = data.get('textures', [])

    # Build UUID -> element map
    elem_map = {el['uuid']: el for el in elements}

    # Build UUID -> group map (CPM format stores bone origin/rotation in groups)
    group_map = {}
    def index_groups(grps):
        for g in grps:
            if isinstance(g, dict) and 'uuid' in g:
                group_map[g['uuid']] = g
                index_groups(g.get('children', []))
    index_groups(groups)

    # Conversion state
    bones = []
    bone_counter = [0]
    used_names = set()
    bone_textures = {}  # bone_name -> set of texture indices

    def unique_bone_name(raw_name, uuid_val=None):
        if raw_name and raw_name not in ('?', 'unnamed', ''):
            name = raw_name
        elif uuid_val:
            name = 'bone_' + uuid_val[:8].replace('-', '')
        else:
            name = f'bone_{bone_counter[0]}'
        if name in CPM_BONE_RENAMES:
            renamed = CPM_BONE_RENAMES[name]
            if renamed not in used_names:
                name = renamed
        base = name
        suffix = 0
        while name in used_names:
            suffix += 1
            name = f'{base}_{suffix}'
        used_names.add(name)
        bone_counter[0] += 1
        return name

    def process_bone(bone_data, parent_name=None):
        if not isinstance(bone_data, dict):
            return

        bone_uuid = bone_data.get('uuid')
        group_data = group_map.get(bone_uuid, {})

        raw_name = bone_data.get('name') or group_data.get('name')
        if exclude_bones and raw_name in exclude_bones:
            print(f'  Excluded bone: {raw_name}')
            return
        visible = bone_data.get('visibility', group_data.get('visibility', True))
        if include_hidden:
            visible = True
        elif not visible and include_bones and raw_name in include_bones:
            visible = True
            print(f'  Force-included hidden bone: {raw_name}')

        bone_name = unique_bone_name(raw_name, bone_uuid)
        origin = group_data.get('origin') or bone_data.get('origin', [0, 0, 0])
        rotation = group_data.get('rotation') or bone_data.get('rotation')

        pivot = origin[:]
        pivot[0] *= -1

        bone = {'name': bone_name, 'pivot': pivot}
        if parent_name:
            bone['parent'] = parent_name
        if rotation and rotation != [0, 0, 0]:
            bone['rotation'] = [-rotation[0], -rotation[1], rotation[2]]

        # Group cubes by texture index
        cubes_by_tex = {}
        for child in bone_data.get('children', []):
            if isinstance(child, str):
                if not visible:
                    continue
                el = elem_map.get(child)
                if not el:
                    continue
                elem_vis = el.get('visibility', True)
                bone_force_included = include_bones and raw_name in include_bones
                if elem_vis is False and not include_hidden and not bone_force_included:
                    continue
                tex_idx = get_elem_texture(el)
                # Compute UV scale when texture resolution differs from model resolution
                uv_scale = None
                if 0 <= tex_idx < len(textures):
                    tex_w = textures[tex_idx].get('width', resolution['width'])
                    tex_h = textures[tex_idx].get('height', resolution['height'])
                    if tex_w != resolution['width'] or tex_h != resolution['height']:
                        uv_scale = (resolution['width'] / tex_w, resolution['height'] / tex_h)
                cube = convert_element_to_cube(el, uv_scale=uv_scale)
                if cube:
                    if tex_idx not in cubes_by_tex:
                        cubes_by_tex[tex_idx] = []
                    cubes_by_tex[tex_idx].append(cube)
            elif isinstance(child, dict):
                process_bone(child, bone_name)

        # If all cubes use the same texture, keep bone as-is
        if len(cubes_by_tex) <= 1:
            all_cubes = []
            for cl in cubes_by_tex.values():
                all_cubes.extend(cl)
            if all_cubes:
                bone['cubes'] = all_cubes
            tex_idx = list(cubes_by_tex.keys())[0] if cubes_by_tex else -1
            bone_textures[bone_name] = {tex_idx}
            bones.append(bone)
        else:
            # Split bone into sub-bones per texture
            bones.append(bone)
            bone_textures[bone_name] = set()
            for tex_idx, tex_cubes in cubes_by_tex.items():
                if tex_idx >= 0 and tex_idx < len(textures):
                    tname = textures[tex_idx]['name'].replace('.png', '').replace(' ', '_')
                else:
                    tname = 'notex'
                sub_name = unique_bone_name(f'{raw_name}_{tname}', None)
                sub_bone = {
                    'name': sub_name,
                    'parent': bone_name,
                    'pivot': pivot[:],
                    'cubes': tex_cubes,
                }
                bones.append(sub_bone)
                bone_textures[sub_name] = {tex_idx}

    for item in outliner:
        process_bone(item, None)

    # Add root bone if needed
    has_root = any(b['name'] == 'root' for b in bones)
    if not has_root:
        bones.insert(0, {'name': 'root', 'pivot': [0, 0, 0]})
        for bone in bones:
            if bone['name'] != 'root' and 'parent' not in bone:
                bone['parent'] = 'root'

    geo = {
        'format_version': '1.12.0',
        'minecraft:geometry': [{
            'description': {
                'identifier': f'geometry.blockpops.{collection_id}.{figure_id}',
                'texture_width': resolution['width'],
                'texture_height': resolution['height'],
                'visible_bounds_width': 4,
                'visible_bounds_height': 4,
                'visible_bounds_offset': [0, 1, 0]
            },
            'bones': bones
        }]
    }

    # Build texture -> bones map
    tex_bone_map = {}
    for bname, tidxs in bone_textures.items():
        for ti in tidxs:
            if ti < 0:
                continue
            if ti not in tex_bone_map:
                tex_bone_map[ti] = []
            tex_bone_map[ti].append(bname)

    return geo, textures, tex_bone_map


def fix_skin_overlay_cubes(bones):
    """
    For skin-format models, add missing inner (base layer) cubes when a bone
    only has overlay cubes (with inflate). Without the inner cube, the body
    appears transparent where the overlay texture has transparent pixels.

    Standard Minecraft skins have two layers per body part:
    - Inner (base): no inflate, base UV region
    - Outer (overlay): with inflate, overlay UV region
    """
    # Overlay north face UV → (u_offset, v_offset) to compute base UVs
    OVERLAY_TO_BASE = {
        (40, 8): (-32, 0),   # Head
        (20, 36): (0, -16),  # Body
        (44, 36): (0, -16),  # Right Arm
        (4, 36): (0, -16),   # Right Leg
        (52, 52): (-16, 0),  # Left Arm
        (4, 52): (16, 0),    # Left Leg
    }

    added = 0
    for bone in bones:
        cubes = bone.get('cubes', [])
        if not cubes:
            continue

        inflated = [c for c in cubes if c.get('inflate')]
        non_inflated = [c for c in cubes if not c.get('inflate')]

        if not inflated or non_inflated:
            continue  # Already has inner cubes or no overlay cubes

        new_inner = []
        for cube in inflated:
            uv = cube.get('uv', {})
            if isinstance(uv, list):
                continue  # box_uv, skip

            north = uv.get('north', {})
            north_uv = north.get('uv')
            if not north_uv:
                continue

            key = (int(round(north_uv[0])), int(round(north_uv[1])))
            offset = OVERLAY_TO_BASE.get(key)
            if offset is None:
                continue

            inner = {
                'origin': cube['origin'][:],
                'size': cube['size'][:],
            }
            if 'rotation' in cube:
                inner['rotation'] = cube['rotation'][:]
            if 'pivot' in cube:
                inner['pivot'] = cube['pivot'][:]
            if 'mirror' in cube:
                inner['mirror'] = cube['mirror']

            inner_uv = {}
            for face_name, face_data in uv.items():
                face_pos = face_data.get('uv', [0, 0])
                face_size = face_data.get('uv_size', [0, 0])
                inner_uv[face_name] = {
                    'uv': [face_pos[0] + offset[0], face_pos[1] + offset[1]],
                    'uv_size': face_size[:]
                }
            inner['uv'] = inner_uv
            new_inner.append(inner)

        if new_inner:
            bone['cubes'] = new_inner + cubes
            added += len(new_inner)
            print(f'  Fixed skin layer: added {len(new_inner)} inner cube(s) to {bone["name"]}')

    return added


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        h.update(f.read())
    return h.hexdigest()


def sanitize_filename(name):
    """Convert texture name to a safe filename."""
    return name.lower().replace('.png', '').replace(' ', '_').replace('(', '').replace(')', '')


def main():
    parser = argparse.ArgumentParser(description='Convert BBModel to GeckoLib geo.json with multi-texture support')
    parser.add_argument('bbmodel_path', help='Path to the .bbmodel file')
    parser.add_argument('figure_id', help='Figure ID (e.g., venus)')
    parser.add_argument('collection_id', help='Collection ID (e.g., willowmedia)')
    parser.add_argument('--output-dir', default='.', help='Output directory for geo.json and textures')
    parser.add_argument('--include-hidden', action='store_true', help='Include hidden bones and elements')
    parser.add_argument('--fix-skin-layers', action='store_true', help='Add missing inner cubes for skin-format overlay-only bones')
    parser.add_argument('--exclude-bones', nargs='+', default=[], help='Bone names to exclude from output (e.g., --exclude-bones tail)')
    parser.add_argument('--include-bones', nargs='+', default=[], help='Force-include specific hidden bones (e.g., --include-bones alt_Lhornbits glasses)')
    args = parser.parse_args()

    bbmodel_dir = os.path.dirname(os.path.abspath(args.bbmodel_path))

    print(f'Converting {args.bbmodel_path}...')
    result = convert_bbmodel_to_geo(args.bbmodel_path, args.figure_id, args.collection_id, args.include_hidden, args.exclude_bones, args.include_bones)
    if result is None:
        print('Conversion failed')
        sys.exit(1)

    geo, textures, tex_bone_map = result

    # Fix missing inner skin layer cubes
    if args.fix_skin_layers:
        fix_skin_overlay_cubes(geo['minecraft:geometry'][0]['bones'])

    # Write geo.json
    os.makedirs(args.output_dir, exist_ok=True)
    geo_path = os.path.join(args.output_dir, f'{args.figure_id}.geo.json')
    with open(geo_path, 'w', encoding='utf-8') as f:
        json.dump(geo, f)

    total_bones = len(geo['minecraft:geometry'][0]['bones'])
    total_cubes = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'])
    print(f'  Model: {total_bones} bones, {total_cubes} cubes ({os.path.getsize(geo_path)} bytes)')

    # Determine primary texture (most bones)
    if not tex_bone_map:
        print('  No textures found')
        return

    primary_idx = max(tex_bone_map.keys(), key=lambda k: len(tex_bone_map[k]))
    primary_tex = textures[primary_idx]
    primary_name = f'{args.figure_id}.png'

    # Copy primary texture
    src = os.path.join(bbmodel_dir, primary_tex['name'])
    dst = os.path.join(args.output_dir, primary_name)
    if os.path.exists(src):
        shutil.copy2(src, dst)
        print(f'  Primary texture: {primary_tex["name"]} -> {primary_name} ({len(tex_bone_map[primary_idx])} bones)')
    else:
        print(f'  WARNING: Primary texture not found: {src}')

    # Copy extra textures and build config
    extra_textures = []
    extra_files = {}
    for tex_idx in sorted(tex_bone_map.keys()):
        if tex_idx == primary_idx:
            continue
        tex = textures[tex_idx]
        bone_names = tex_bone_map[tex_idx]
        safe_name = f'{args.figure_id}_{sanitize_filename(tex["name"])}.png'

        src = os.path.join(bbmodel_dir, tex['name'])
        dst = os.path.join(args.output_dir, safe_name)
        if os.path.exists(src):
            shutil.copy2(src, dst)
            extra_files[safe_name] = dst
        else:
            print(f'  WARNING: Texture not found: {src}')

        tex_path = f'blockpops:textures/block/figure/{args.collection_id}/{safe_name}'
        extra_textures.append({
            'texture': tex_path,
            'bones': bone_names
        })
        print(f'  Extra texture: {tex["name"]} -> {safe_name} ({len(bone_names)} bones)')

    # Generate collection JSON snippet
    print(f'\n=== Collection JSON for {args.figure_id} ===')
    figure_json = {
        'id': args.figure_id,
        'name': args.figure_id.capitalize(),
        'model': f'blockpops:geo/figure/{args.collection_id}/{args.figure_id}.geo.json',
        'texture': f'blockpops:textures/block/figure/{args.collection_id}/{primary_name}',
        'animation': 'blockpops:animations/figure/box_figure_default.animation.json',
        'scale': 0.29,
    }
    if extra_textures:
        figure_json['extra_textures'] = extra_textures

    print(json.dumps(figure_json, indent=2))

    # List all output files
    print(f'\n=== Output files ===')
    print(f'  {geo_path}')
    print(f'  {os.path.join(args.output_dir, primary_name)}')
    for name in extra_files:
        print(f'  {os.path.join(args.output_dir, name)}')


if __name__ == '__main__':
    main()
