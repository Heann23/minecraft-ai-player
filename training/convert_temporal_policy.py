"""Explicit separate-catalog conversion of trained v7 policy selection law.

Preserves all tensors/Adam/RNG/counters but changes probabilities for running
actions. This is an architecture prior, not new learning or learned ability.
"""
import argparse,copy,hashlib,json
from pathlib import Path
import brain_session,checkpoint_store
from model_catalog import ModelCatalog,verify_state,model_name
from train_paper_ppo import resume_checkpoint
from continuation_policy import SELECTION

def convert(source: Path, output: Path, name: str):
    state=brain_session.load(source);verify_state(state)
    if state['contract']!='minecraft-primitive-candidates/v7' or state.get('contextUpgrade') or state['initializationOnly'] or state['pendingRolloutTransitions']:
        raise ValueError('conversion requires trained native v7 with no pending; originals preserved')
    if output.exists():raise ValueError('conversion output must be new')
    cp=Path(state['checkpoint']);model,parent=resume_checkpoint(cp)
    if model.selection!='flat':raise ValueError('source must use flat selection')
    checkpoint=json.loads(cp.read_bytes());report=json.loads((cp.parent/'training-report.json').read_bytes())
    before=copy.deepcopy(model.optimizer_state());model.selection=SELECTION;after=model.optimizer_state()
    assert {k:v for k,v in after.items() if k!='selection'}==before
    raw=cp.parent.parent.parent/'paper-rollouts.jsonl' if report.get('rolloutFile') else cp.parent/'paper-rollouts.jsonl'
    payload=raw.read_bytes()[:report['rolloutBytesUsed']]
    if hashlib.sha256(payload).hexdigest()!=checkpoint['rolloutSha256']:raise ValueError('source prefix mismatch')
    output.mkdir(parents=True,exist_ok=False)
    checkpoint_store.durable_replace(output/'paper-rollouts.jsonl',payload)
    checkpoint['optimizer']=after
    artifact=model.artifact(environment_steps=state['trainedEnvironmentSteps'],training_run=checkpoint['run'],rollout_sha256=checkpoint['rolloutSha256'])
    artifact.update(brainId=state['brainId'],modelVersion=model.version,gradientUpdates=model.updates,trainingStartState='resume')
    bundle=checkpoint_store.publish(output,artifact,checkpoint,report)
    destination=bundle/'optimizer-checkpoint.json'
    migrated=dict(state,checkpoint=str(destination.resolve()),checkpointSha256=hashlib.sha256(destination.read_bytes()).hexdigest(),modelName=model_name(name))
    verify_state(migrated)
    brain_session.save(output/'runtime-session.json',migrated,recoverable=True)
    revision=ModelCatalog(output/'catalog').save(name,migrated)
    evidence={'schema':'minecraft-temporal-policy-conversion/v1','sourceSession':state,'convertedSession':migrated,'selection':SELECTION,'allTensorsAdamCheckpointRngAndCountersPreserved':True,'sessionRngPreserved':state['rng']==migrated['rng'],'initialProbabilitiesPreserved':False,'architecturePriorChanged':True,'newLearning':False,'abilityProven':False,'sourcePrefixSha256':hashlib.sha256(payload).hexdigest()}
    checkpoint_store.durable_replace(output/'conversion-evidence.json',checkpoint_store.encoded(evidence))
    return revision

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--name',type=model_name,required=True)
    args=parser.parse_args();print(json.dumps(convert(args.session,args.output,args.name)))
