"""
Convert BBModel (Figura/CPM format) to Bedrock Geometry JSON (GeckoLib compatible).
"""
import json
import os
import hashlib


    # CPM bone name -> PascalCase standard name mapping
CPM_BONE_RENAMES = {
    'head': 'Head',
    'body': 'Body',
    'left_arm': 'LeftArm',
    'right_arm': 'RightArm',
    'left_leg': 'LeftLeg',
    'right_leg': 'RightLeg',
}


def convert_bbmodel_to_geo(bbmodel_path, figure_id, collection_id):
    with open(bbmodel_path, 'r', encoding='utf-8') as f:
        data = json.load(f)

    resolution = data.get('resolution', {'width': 64, 'height': 64})
    elements = data.get('elements', [])
    outliner = data.get('outliner', [])
    groups = data.get('groups', [])

    # Check for mesh elements (can't convert these)
    has_mesh = any(e.get('type') == 'mesh' for e in elements)
    if has_mesh:
        print(f"  SKIP {figure_id}: contains mesh elements (not convertible)")
        return None

    # Build UUID -> element map
    elem_map = {}
    for el in elements:
        elem_map[el['uuid']] = el

    # Build UUID -> group map (CPM format stores bone origin/rotation in groups, not outliner)
    group_map = {}
    def index_groups(grps):
        for g in grps:
            if isinstance(g, dict) and 'uuid' in g:
                group_map[g['uuid']] = g
                index_groups(g.get('children', []))
    index_groups(groups)

    # Convert outliner tree to flat bone list
    bones = []
    bone_counter = [0]
    used_names = set()
    # Track renames so child bones reference the correct parent
    rename_map = {}

    def unique_bone_name(raw_name, uuid_val=None):
        """Generate a unique bone name, using UUID prefix for unnamed bones."""
        if raw_name and raw_name not in ('?', 'unnamed', ''):
            name = raw_name
        elif uuid_val:
            name = 'bone_' + uuid_val[:8].replace('-', '')
        else:
            name = f'bone_{bone_counter[0]}'
        # Rename CPM bones to PascalCase standard names
        if name in CPM_BONE_RENAMES:
            renamed = CPM_BONE_RENAMES[name]
            if renamed not in used_names:
                rename_map[name] = renamed
                name = renamed
        # Ensure uniqueness
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

        # Merge with groups data (CPM stores origin/rotation/name there)
        bone_uuid = bone_data.get('uuid')
        group_data = group_map.get(bone_uuid, {})

        # Check visibility - hidden bones in Blockbench have visibility: false
        # Default to True if not specified
        visible = bone_data.get('visibility', group_data.get('visibility', True))

        # Prefer group data for name, origin, rotation (CPM outliner is stripped)
        raw_name = bone_data.get('name') or group_data.get('name')
        bone_name = unique_bone_name(raw_name, bone_uuid)
        origin = group_data.get('origin') or bone_data.get('origin', [0, 0, 0])
        rotation = group_data.get('rotation') or bone_data.get('rotation')

        # Bedrock format negates pivot X (Blockbench compileGroup: pivot[0] *= -1)
        pivot = origin[:]
        pivot[0] *= -1

        bone = {
            'name': bone_name,
            'pivot': pivot,
        }

        if parent_name:
            bone['parent'] = parent_name

        if rotation and rotation != [0, 0, 0]:
            # GeckoLib negates X and Y when loading: toRadians(-x), toRadians(-y), toRadians(z)
            # So we must pre-negate X and Y for correct display
            bone['rotation'] = [-rotation[0], -rotation[1], rotation[2]]

        # Only collect cubes if the bone is visible
        cubes = []
        for child in bone_data.get('children', []):
            if isinstance(child, str):
                # Skip cubes for hidden bones
                if not visible:
                    continue
                el = elem_map.get(child)
                if el and el.get('type', 'cube') == 'cube' and el.get('visibility', True) is not False:
                    cube = convert_element_to_cube(el, resolution)
                    if cube:
                        cubes.append(cube)
            elif isinstance(child, dict):
                # Always process child bones (they may be visible even if parent is hidden)
                process_bone(child, bone_name)

        if cubes:
            bone['cubes'] = cubes

        bones.append(bone)

    for item in outliner:
        process_bone(item, None)

    # Add a 'root' bone if one doesn't exist, parenting all top-level bones to it
    has_root = any(b['name'] == 'root' for b in bones)
    if not has_root:
        # Insert root bone at the beginning
        bones.insert(0, {'name': 'root', 'pivot': [0, 0, 0]})
        # Re-parent all top-level bones (those without a parent) to root
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

    return geo


def convert_element_to_cube(el, resolution):
    from_pos = el.get('from', [0, 0, 0])
    to_pos = el.get('to', [0, 0, 0])

    size = [to_pos[i] - from_pos[i] for i in range(3)]
    # Bedrock format negates origin X: origin[0] = -(from[0] + size[0])
    origin = from_pos[:]
    origin[0] = -(origin[0] + size[0])

    cube = {
        'origin': origin,
        'size': size,
    }

    inflate = el.get('inflate')
    if inflate and inflate != 0:
        cube['inflate'] = inflate

    rotation = el.get('rotation')
    if rotation and rotation != [0, 0, 0]:
        # Bedrock format: negate rotation X and Y, negate pivot X
        cube['rotation'] = [-rotation[0], -rotation[1], rotation[2]]
        pivot = el.get('origin', [0, 0, 0])[:]
        pivot[0] *= -1
        cube['pivot'] = pivot

    faces = el.get('faces', {})
    box_uv = el.get('box_uv', False)

    if box_uv:
        uv_offset = el.get('uv_offset', [0, 0])
        cube['uv'] = uv_offset
    else:
        uv = {}
        face_names = ['north', 'east', 'south', 'west', 'up', 'down']
        for face_name in face_names:
            face = faces.get(face_name)
            if face and face.get('texture') is not None:
                face_uv = face.get('uv', [0, 0, 0, 0])
                uv[face_name] = {
                    'uv': [face_uv[0], face_uv[1]],
                    'uv_size': [face_uv[2] - face_uv[0], face_uv[3] - face_uv[1]]
                }
        cube['uv'] = uv

    if el.get('mirror_uv'):
        cube['mirror'] = True

    return cube


def find_bbmodel(directory):
    for root, dirs, files in os.walk(directory):
        for f in files:
            if f.endswith('.bbmodel'):
                return os.path.join(root, f)
    return None


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        h.update(f.read())
    return h.hexdigest()


def main():
    willow_dir = 'C:/Users/nebur/WillowMedia'
    staging_dir = 'C:/Users/nebur/blockpops-r2-staging'
    collection_id = 'willowmedia'

    # Map directory names to figure IDs
    bbmodel_dirs = {}
    for d in sorted(os.listdir(willow_dir)):
        if '_BBModel' in d or '_Model' in d:
            parts = d.split('_', 1)
            if len(parts) >= 2:
                name = parts[1].replace('_BBModel', '').replace('_Model', '').lower()
                bbmodel_dirs[name] = os.path.join(willow_dir, d)

    # Output directory for geo files
    geo_out_dir = os.path.join(staging_dir, f'assets/blockpops/geckolib/models/figure/{collection_id}')
    os.makedirs(geo_out_dir, exist_ok=True)

    converted = {}
    skipped = []

    for figure_id, directory in sorted(bbmodel_dirs.items()):
        bbmodel_path = find_bbmodel(directory)
        if not bbmodel_path:
            print(f"  SKIP {figure_id}: no .bbmodel file found")
            skipped.append(figure_id)
            continue

        print(f"  Converting {figure_id} from {os.path.basename(bbmodel_path)}...")
        geo = convert_bbmodel_to_geo(bbmodel_path, figure_id, collection_id)

        if geo is None:
            skipped.append(figure_id)
            continue

        geo_path = os.path.join(geo_out_dir, f'{figure_id}.geo.json')
        with open(geo_path, 'w', encoding='utf-8') as f:
            json.dump(geo, f, indent=2)

        file_size = os.path.getsize(geo_path)
        num_bones = len(geo['minecraft:geometry'][0]['bones'])
        total_cubes = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'])
        print(f"    -> {file_size} bytes, {num_bones} bones, {total_cubes} cubes")

        converted[figure_id] = {
            'geo_path': geo_path,
            'geckolib_model': f'blockpops:figure/{collection_id}/{figure_id}',
        }

    print(f"\nConverted: {len(converted)}")
    print(f"Skipped: {len(skipped)} ({', '.join(skipped)})")

    # Update collection JSON
    collection_path = os.path.join(staging_dir, f'data/blockpops/collections/{collection_id}.json')
    with open(collection_path, 'r', encoding='utf-8') as f:
        collection = json.load(f)

    updated_count = 0
    for figure in collection.get('figures', []):
        fig_id = figure.get('id', '')
        if fig_id in converted:
            figure['model'] = f'blockpops:geo/figure/{collection_id}/{fig_id}.geo.json'
            updated_count += 1
            print(f"  Updated {fig_id} model path")

    with open(collection_path, 'w', encoding='utf-8') as f:
        json.dump(collection, f, indent=2)

    print(f"\nUpdated {updated_count} figures in collection JSON")

    # Rebuild manifest
    manifest_path = os.path.join(staging_dir, 'manifest.json')
    files_list = []

    for root, dirs, files in os.walk(staging_dir):
        for fname in files:
            full_path = os.path.join(root, fname)
            rel_path = os.path.relpath(full_path, staging_dir).replace('\\', '/')
            if rel_path == 'manifest.json':
                continue
            sha = sha256_file(full_path)
            files_list.append({'path': rel_path, 'sha256': sha})

    manifest = {
        'version': 2,
        'collections': [{'id': collection_id, 'name': 'WillowMedia'}],
        'files': sorted(files_list, key=lambda x: x['path'])
    }

    with open(manifest_path, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, indent=2)

    print(f"\nManifest updated with {len(files_list)} files")

    print("\n=== NEW GEO FILES ===")
    for fig_id in sorted(converted.keys()):
        print(f"  assets/blockpops/geckolib/models/figure/{collection_id}/{fig_id}.geo.json")


if __name__ == '__main__':
    main()
