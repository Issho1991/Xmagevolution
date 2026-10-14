from pathlib import Path
import base64, copy, json, subprocess, sys, tempfile, unittest, zipfile
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import pipeline
import promote

def proof():
    return {'schema':1,'repository':pipeline.TARGET,'workflowPath':'.github/workflows/automatic-upstream.yml',
            'repositoryCommit':'a'*40,'workflowRunId':1,
            'spec':{'upstreamReleaseId':2,'id':'lab-10.0-u2-ai6','labVersion':'10.0.u2',
                    'packageName':'Pacchetto_Lab_10_0_u2_per_Launcher.zip','xmageVersion':'1.4.63-V1','replay':False},
            'package':{'name':'Pacchetto_Lab_10_0_u2_per_Launcher.zip','sha256':'a'*64,'inventorySha256':'b'*64,'bytes':100},
            'tests':{'tests':148,'failures':0,'errors':0,'skipped':1,'passed':147},
            'installation':{'backupActivationServerRollback':True},'jarCount':195}

class Gates(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)

    def merge(self,base,ours,theirs):
        for name,text in [('base',base),('ours',ours),('theirs',theirs)]:
            if text is not None:(self.root/name).write_text(text)
        return pipeline.merge_one(self.root/'ours',self.root/'base',self.root/'theirs')

    def test_non_overlapping_official_and_lab_changes_survive(self):
        base='first\n'+''.join('line'+str(i)+'\n' for i in range(12))+'last\n'
        result=self.merge(base,base.replace('first','laboratory'),base.replace('last','official'))
        self.assertTrue(result.startswith(b'laboratory\n'));self.assertTrue(result.endswith(b'official\n'))

    def test_conflicting_change_stops(self):
        with self.assertRaises(ValueError):self.merge('old\n','lab\n','official\n')

    def test_removed_customized_source_stops(self):
        with self.assertRaises(ValueError):self.merge('old\n','lab\n',None)

    def test_upstream_collision_with_new_lab_class_stops(self):
        with self.assertRaises(ValueError):self.merge(None,'lab\n','official\n')

    def test_new_lab_class_is_retained(self):
        self.assertEqual(self.merge(None,'lab\n',None),b'lab\n')

    def test_zip_traversal_and_case_collisions_stop(self):
        for names in [['../outside'],['/absolute'],['a\\outside'],['C:/escape'],['same','SAME']]:
            z=self.root/'test.zip'
            with zipfile.ZipFile(z,'w') as out:
                for name in names:out.writestr(name,b'x')
            with self.assertRaises(ValueError):pipeline.validate_zip(z)

    def test_regression_abort_or_missing_tests_stop(self):
        d=self.root/'junit';d.mkdir()
        for tests,skipped,errors,failures in [(148,2,0,0),(147,0,0,0),(148,1,1,0),(148,1,0,1)]:
            (d/'tests.xml').write_text(f'<testsuite tests="{tests}" skipped="{skipped}" errors="{errors}" failures="{failures}"/>')
            with self.assertRaises(ValueError):pipeline.tests_summary(self.root)

    def test_replay_is_never_publishable(self):
        p=proof();p['spec']['replay']=True
        with self.assertRaises(ValueError):promote.validate_proof(p)

    def test_failed_regressions_or_smoke_cannot_promote(self):
        for field in ['failed','skipped','smoke','identity','provenance']:
            p=proof()
            if field=='failed':p['tests']['failures']=1
            if field=='skipped':p['tests']['skipped']=2
            if field=='smoke':p['installation']['backupActivationServerRollback']=False
            if field=='identity':p['spec']['id']='different'
            if field=='provenance':p['workflowRunId']=0
            with self.assertRaises(ValueError):promote.validate_proof(p)

    def test_complete_proof_passes(self):
        promote.validate_proof(proof())

    def test_modified_package_cannot_promote(self):
        p=self.root/'package.zip'
        with zipfile.ZipFile(p,'w') as z:z.writestr('package.properties','tampered')
        with self.assertRaises(ValueError):promote.validate_package(p,proof())

    def test_official_digest_and_asset_origin_required(self):
        r={'id':2,'tag_name':'xmage_1.4.63V1','draft':False,'prerelease':False,'assets':[{'name':'mage-full_1.4.63-dev_2026-10-14_10-00.zip','size':100,'digest':None,'browser_download_url':'https://example.com/evil.zip'}]}
        with self.assertRaises(ValueError):pipeline.release_spec(r,'a'*40)
        r['assets'][0]['digest']='sha256:'+'a'*64
        with self.assertRaises(ValueError):pipeline.release_spec(r,'a'*40)

    def test_version_order_does_not_downgrade(self):
        self.assertGreater(pipeline.version_tuple('1.4.63-V1'),pipeline.version_tuple('1.4.62-V2'))
        self.assertGreater(pipeline.version_tuple('1.4.62-V2'),pipeline.version_tuple('1.4.62-V1'))
        with self.assertRaises(ValueError):pipeline.version_tuple('1.4.63-dev')

    def test_existing_channel_signature_is_verified(self):
        payload=pipeline.signed_payload(pipeline.REPO/'updates/catalog.properties')
        self.assertEqual(payload['product'],'xmage-laboratorio')
        bad=self.root/'catalog.properties';raw=(pipeline.REPO/'updates/catalog.properties').read_text()
        envelope=pipeline.properties(raw.encode());payload_bytes=base64.b64decode(envelope['payload'])+b'x'
        bad.write_text(raw.replace(envelope['payload'],base64.b64encode(payload_bytes).decode()))
        with self.assertRaises(subprocess.CalledProcessError):pipeline.signed_payload(bad)

    def test_signing_roundtrip_and_wrong_key(self):
        key=self.root/'disposable.pem'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(key)],check=True,capture_output=True)
        public=subprocess.check_output(['openssl','pkey','-in',str(key),'-pubout','-outform','DER'],stderr=subprocess.DEVNULL)
        resources=self.root/'launcher/resources';resources.mkdir(parents=True)
        (resources/'lab-updates-public.der.b64').write_text(base64.b64encode(public).decode())
        report=self.root/'proof.json';report.write_text(json.dumps(proof()));catalog=self.root/'signed.properties'
        with patch.object(pipeline,'HERE',self.root),patch.object(promote,'HERE',self.root):
            promote.sign(proof(),key,4,'https://example.com/package.zip',catalog,report)
            self.assertEqual(pipeline.signed_payload(catalog)['serial'],'4')
        with self.assertRaises(ValueError):promote.sign(proof(),key,4,'https://example.com/package.zip',self.root/'rejected.properties',report)
        self.assertFalse((self.root/'rejected.properties').exists())

if __name__=='__main__':unittest.main()
